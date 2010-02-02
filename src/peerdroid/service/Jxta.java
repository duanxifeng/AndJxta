package peerdroid.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

import net.jxta.endpoint.Message;
import net.jxta.id.IDFactory;
import net.jxta.peer.PeerID;
import net.jxta.peergroup.PeerGroup;
import net.jxta.peergroup.PeerGroupID;
import net.jxta.pipe.OutputPipe;
import net.jxta.pipe.PipeMsgEvent;
import net.jxta.pipe.PipeMsgListener;
import net.jxta.pipe.PipeService;
import net.jxta.platform.ConfigurationFactory;
import net.jxta.platform.NetworkConfigurator;
import net.jxta.platform.NetworkManager;
import net.jxta.protocol.PipeAdvertisement;
import peerdroid.jxta4android.JxtaApp;
import android.util.Log;

public class Jxta implements PipeMsgListener {
	private NetworkConfigurator configurator;
	private NetworkManager networkManager;
	private PeerGroup netPeerGroup;
	private String exitlock = new String("exitlock");
	//private static URI rdvlist = new File("seeds.txt").toURI();
	private static String rdvlist = "http://192.168.178.74/seeds.txt";
	private boolean actAsRendezvous;
	private final static long PIPE_ESTABLISHING_TIMEOUT = 1 * 60 * 1000;
	
	private Discovery discovery;
	private HashMap<String, OutputPipe> establishedPipes;
	private FileTransfer fileTransferService;
	private TextTransfer textTransferService;
	
	private String instanceName;
	private File cacheHome;
	private String username;
	private String password;
	private String description;
	
	public static final String MESSAGE_TYPE_TEXT = "TEXT";
	public static final String MESSAGE_TYPE_FILE = "FILE";
	
	public void setup(String name, File cache, String user,
			String pass, String rendezvousServerListURI) {
		instanceName = name;
		cacheHome = cache;
		username = user;
		password = pass;
		rdvlist = rendezvousServerListURI;
	}
	
	public void start111(String name, File cache, String user,
			String pass) {
		instanceName = name;
		cacheHome = cache;
		username = user;
		password = pass;

		configureJXTA();
		try {
			startJXTA();
		} catch (Throwable e) {
			e.printStackTrace();
		}
		
		startDiscovery();
		
		//waitForInput();
		waitForQuit();
	}

	public void stop() {
		for (OutputPipe pipe : establishedPipes.values()) {
			if (!pipe.isClosed())
				pipe.close();
		}
		
		netPeerGroup.stopApp();
	}

	public void configureJXTA() {
		try {
			networkManager = new NetworkManager(NetworkManager.EDGE,
					instanceName, new File(cacheHome, instanceName).toURI());

			configurator = networkManager.getConfigurator();
		} catch (IOException e) {
			e.printStackTrace();
		}

		PeerID peerId = IDFactory.newPeerID(PeerGroupID.defaultNetPeerGroupID);
		
		{ // for Android
			ConfigurationFactory.setHome(new File(cacheHome, instanceName));
			ConfigurationFactory.setPeerID(peerId);
			ConfigurationFactory.setName(instanceName);
			ConfigurationFactory.setUseMulticast(true);
			try {
				ConfigurationFactory.setRdvSeedingURI(new URI(rdvlist));
				ConfigurationFactory.setRelaySeedingURI(new URI(rdvlist));
			} catch (URISyntaxException e) {
				e.printStackTrace();
			}
			ConfigurationFactory factory = ConfigurationFactory.newInstance();
			factory.setUseOnlyRendezvousSeeds(true);
			factory.setUseOnlyRelaySeeds(true);
		}
		
		/*
		{ // for Windows
			configurator.setHome(new File(cacheHome, instanceName));
			configurator.setPeerID(peerId);
			configurator.setName(instanceName);
			configurator.setPrincipal(username);
			//configurator.setPassword(password);
			//configurator.setDescription("A P2P Peer");
			configurator.setUseMulticast(true);
			configurator.addRdvSeedingURI(rdvlist);
			configurator.addRelaySeedingURI(rdvlist);
			//configurator.setUseOnlyRelaySeeds(true);
			configurator.setUseOnlyRendezvousSeeds(true);
		}
		*/

		/*
		try {
			configurator.save();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		*/
		Log.d(JxtaApp.TAG, "Platform configured and saved");
	}
	
	/**
	 * @param actAsRendezvous
	 *            If true, this peer starts a rendezvous peer without trying to
	 *            connect to another (not implemented in PeerDroid!)
	 */
	public void setActAsRendezvous(boolean actAsRendezvous) {
		this.actAsRendezvous = actAsRendezvous;
	}
	
	public void startJXTA() throws Throwable {
		Log.d(JxtaApp.TAG, "Starting network...");
		netPeerGroup = networkManager.startNetwork();
		Log.d(JxtaApp.TAG, "Network started");

		if (actAsRendezvous) {
			netPeerGroup.getRendezVousService().startRendezVous();
			Log.d(JxtaApp.TAG, "Rendezvous peer started");
		} else {
			Rendezvous rendezvousService = new Rendezvous(netPeerGroup);
			rendezvousService.waitForRdv();
		}
		
		establishedPipes = new HashMap<String, OutputPipe>();
		
		fileTransferService = new FileTransfer(netPeerGroup.getPeerID().toString(), instanceName);
		textTransferService = new TextTransfer(netPeerGroup.getPeerID().toString(), instanceName);
	}
	
	public void startDiscovery() {
		Thread discoveryServerThread = new Thread(discovery = new Discovery(networkManager, instanceName, this), "Discovery Thread");
		discoveryServerThread.start();
	}
	
	private void waitForInput() {
		final BufferedReader stdin = new BufferedReader(
				new InputStreamReader(System.in));
		
		new Thread("Send Thread") {
			public void run() {
				while (true) {
					String peername;
					String message;
					
					try {
						System.out.print("send to peer-name: ");
						peername = stdin.readLine();
						System.out.println(peername);

						System.out.print("send message ('file' for file-transfer): ");
						message = stdin.readLine();
						
						sendMsgToPeer(peername, message, MESSAGE_TYPE_TEXT);
					} catch (IOException e2) {
						e2.printStackTrace();
					}
				}
			}
		}.start();
	}
	
	public boolean sendMsgToPeer(String peername, String message, String messageType) {
		PipeAdvertisement peer = getPipeAdvertisementByName(peername);
		
		if (peer == null) {
			Log.d(JxtaApp.TAG, "Peer not found while discovery");
			return false;
		}
		
		OutputPipe pipe = setupPipe(peer);
		
		if (pipe == null) {
			Log.d(JxtaApp.TAG, "Cannot setup pipe to this peer");
			return false;
		}
		
		if (messageType.equals(MESSAGE_TYPE_FILE))
			fileTransferService.sendFile(pipe, message);
		else //if (messageType.equals(MESSAGE_TYPE_TEXT))
			textTransferService.sendText(pipe, message);

		// don't close, hold all open pipes for later use
		// closePipe(pipe, peer);
		
		return true;
	}
	
	public PipeAdvertisement getPipeAdvertisementByName(String peername) {
		PipeAdvertisement peer = null;
		return getPeerByName(peername).getPipeAdvertisement();
	}
	
	public Peer getPeerByName(String peername) {
		Peer peer = null;
		
		Log.d(JxtaApp.TAG, "Try to find peer in discovery list...");
		
		synchronized (discovery.getPeerList()) {
			for (int i = 0; i < discovery.getPeerList().size(); i++) {
				//Log.d(JXTA4JSE.TAG, discoveryClient.getPeerList().get(i).getName() + " == " + peername);
				if (discovery.getPeerList().get(i).getName() != null && discovery.getPeerList().get(i).getName().equals(peername)) {
					peer = discovery.getPeerList().get(i);
				}
			}
		}
		
		if (peer != null) {
			Log.d(JxtaApp.TAG, "Find peer " + peername + " in discovery list");
		}
		
		return peer;
	}

	private OutputPipe setupPipe(PipeAdvertisement peerAdv) {
		if (establishedPipes.containsKey(peerAdv.getName())) {
			OutputPipe pipe = establishedPipes.get(peerAdv.getName());
			if (!pipe.isClosed()) {
				Log.d(JxtaApp.TAG, "Pipe already exist, use established pipe");
				return pipe;
			}
		}
		
		Log.d(JxtaApp.TAG, "Try to establish pipe to peer...");

		PipeService pipeService = netPeerGroup.getPipeService();
		OutputPipe outputPipe = null;
		try {
			outputPipe = pipeService.createOutputPipe(peerAdv, PIPE_ESTABLISHING_TIMEOUT);
		} catch (IOException e) {
			e.printStackTrace();
			//JxtaApp.txtChatHistory.append("\n" + e);
			return null;
		}
		
		if (outputPipe != null) {
			establishedPipes.put(peerAdv.getName(), outputPipe);
			Log.d(JxtaApp.TAG, "Pipe to peer " + peerAdv.getName() + " established");
		}
		
		return outputPipe;
	}
	
	private void closePipe(OutputPipe pipe, PipeAdvertisement peerAdv) {
		pipe.close();
		
		//establishedPipes.remove(peerAdv.getName());
		Log.d(JxtaApp.TAG, "Pipe to peer closed.");
	}
	
	/**
	 * 
	 */
	public void pipeMsgEvent(PipeMsgEvent event) {
		Message msg = event.getMessage();

		if (msg.getMessageElement("Type").toString().equals(MESSAGE_TYPE_TEXT)) {
			textTransferService.receiveText(this, msg);
		} else if (msg.getMessageElement("Type").toString().equals(
				MESSAGE_TYPE_FILE)) {
			fileTransferService.receiveFilePackage(this, msg);
		} else {
			Log.d(JxtaApp.TAG, "No Service for this message type!");
		}
	}

	private void waitForQuit() {
		synchronized (exitlock) {
			try {
				Log.d(JxtaApp.TAG, "waiting for quit");
				exitlock.wait();
			} catch (InterruptedException e) {
				;
			}
		}
	}
	
	public Discovery getDiscovery() {
		return discovery;
	}
}
