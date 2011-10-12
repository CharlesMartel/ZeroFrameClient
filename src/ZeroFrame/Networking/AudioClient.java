package ZeroFrame.Networking;

import java.awt.Dimension;
import java.io.IOException;
import java.net.InetAddress;

import javax.media.Codec;
import javax.media.Control;
import javax.media.Controller;
import javax.media.ControllerClosedEvent;
import javax.media.ControllerEvent;
import javax.media.ControllerListener;
import javax.media.Format;
import javax.media.MediaLocator;
import javax.media.NoProcessorException;
import javax.media.Owned;
import javax.media.Player;
import javax.media.Processor;
import javax.media.control.QualityControl;
import javax.media.control.TrackControl;
import javax.media.format.VideoFormat;
import javax.media.protocol.ContentDescriptor;
import javax.media.protocol.DataSource;
import javax.media.protocol.PushBufferDataSource;
import javax.media.protocol.PushBufferStream;
import javax.media.rtp.RTPManager;
import javax.media.rtp.SendStream;
import javax.media.rtp.SessionAddress;
import javax.media.rtp.rtcp.SourceDescription;

/**
 * @author Hammer
 *
 */
public class AudioClient extends Thread {
	
	private AVTransmit2 transmitter = null;
	
	public AudioClient(String clientIP, String clientPort){
		//transmitter = new AVTransmit2(locator, clientIP, clientPort, format);
	}
	
	public void run(){
		
	}

	private class AVTransmit2 {

		// Input MediaLocator
		// Can be a file or http or capture source
		public MediaLocator locator;
		public String ipAddress;
		public int portBase;

		public Processor processor = null;
		public RTPManager rtpMgrs[];
		public DataSource dataOutput = null;

		public AVTransmit2(MediaLocator locator, String ipAddress, String pb,
				Format format) {

			this.locator = locator;
			this.ipAddress = ipAddress;
			Integer integer = Integer.valueOf(pb);
			if (integer != null)
				this.portBase = integer.intValue();
		}

		/**
		 * Starts the transmission. Returns null if transmission started ok.
		 * Otherwise it returns a string with the reason why the setup failed.
		 */
		public synchronized String start() {
			String result;

			// Create a processor for the specified media locator
			// and program it to output JPEG/RTP
			result = createProcessor();
			if (result != null)
				return result;

			// Create an RTP session to transmit the output of the
			// processor to the specified IP address and port no.
			result = createTransmitter();
			if (result != null) {
				processor.close();
				processor = null;
				return result;
			}

			// Start the transmission
			processor.start();

			return null;
		}

		/**
		 * Stops the transmission if already started
		 */
		public void stop() {
			synchronized (this) {
				if (processor != null) {
					processor.stop();
					processor.close();
					processor = null;
					for (int i = 0; i < rtpMgrs.length; i++) {
						rtpMgrs[i].removeTargets("Session ended.");
						rtpMgrs[i].dispose();
					}
				}
			}
		}

		private String createProcessor() {
			if (locator == null)
				return "Locator is null";

			DataSource ds;
			DataSource clone;

			try {
				ds = javax.media.Manager.createDataSource(locator);
			} catch (Exception e) {
				return "Couldn't create DataSource";
			}

			// Try to create a processor to handle the input media locator
			try {
				processor = javax.media.Manager.createProcessor(ds);
			} catch (NoProcessorException npe) {
				return "Couldn't create processor";
			} catch (IOException ioe) {
				return "IOException creating processor";
			}

			// Wait for it to configure
			boolean result = waitForState(processor, Processor.Configured);
			if (result == false)
				return "Couldn't configure processor";

			// Get the tracks from the processor
			TrackControl[] tracks = processor.getTrackControls();

			// Do we have atleast one track?
			if (tracks == null || tracks.length < 1)
				return "Couldn't find tracks in processor";

			// Set the output content descriptor to RAW_RTP
			// This will limit the supported formats reported from
			// Track.getSupportedFormats to only valid RTP formats.
			ContentDescriptor cd = new ContentDescriptor(ContentDescriptor.RAW_RTP);
			processor.setContentDescriptor(cd);

			Format supported[];
			Format chosen;
			boolean atLeastOneTrack = false;

			// Program the tracks.
			for (int i = 0; i < tracks.length; i++) {
				Format format = tracks[i].getFormat();
				if (tracks[i].isEnabled()) {

					supported = tracks[i].getSupportedFormats();
					for(Format curr : supported){
						System.err.println(curr);
					}
					// We've set the output content to the RAW_RTP.
					// So all the supported formats should work with RTP.
					// We'll just pick the first one.

					if (supported.length > 0) {
						chosen = supported[3];
						tracks[i].setFormat(chosen);
						System.err.println("Track " + i + " is set to transmit as:");
						System.err.println("  " + chosen);
						atLeastOneTrack = true;
					} else
						tracks[i].setEnabled(false);
				} else
					tracks[i].setEnabled(false);
			}

			if (!atLeastOneTrack)
				return "Couldn't set any of the tracks to a valid RTP format";

			// Realize the processor. This will internally create a flow
			// graph and attempt to create an output datasource for JPEG/RTP
			// audio frames.
			result = waitForState(processor, Controller.Realized);
			if (result == false)
				return "Couldn't realize processor";

			// Get the output data source of the processor
			dataOutput = processor.getDataOutput();

			return null;
		}

		/**
		 * Use the RTPManager API to create sessions for each media track of the
		 * processor.
		 */
		private String createTransmitter() {

			// Cheated. Should have checked the type.
			PushBufferDataSource pbds = (PushBufferDataSource) dataOutput;
			PushBufferStream pbss[] = pbds.getStreams();

			rtpMgrs = new RTPManager[pbss.length];
			SessionAddress localAddr, destAddr;
			InetAddress ipAddr;
			SendStream sendStream;
			int port;
			SourceDescription srcDesList[];

			for (int i = 0; i < pbss.length; i++) {
				try {
					rtpMgrs[i] = RTPManager.newInstance();

					// The local session address will be created on the
					// same port as the the target port. This is necessary
					// if you use AVTransmit2 in conjunction with JMStudio.
					// JMStudio assumes - in a unicast session - that the
					// transmitter transmits from the same port it is receiving
					// on and sends RTCP Receiver Reports back to this port of
					// the transmitting host.

					port = portBase + 2 * i;
					ipAddr = InetAddress.getByName(ipAddress);

					localAddr = new SessionAddress( InetAddress.getLocalHost(), SessionAddress.ANY_PORT);
					
					destAddr = new SessionAddress(ipAddr, port);

					rtpMgrs[i].initialize(localAddr);

					rtpMgrs[i].addTarget(destAddr);

					System.err.println("Created RTP session: " + ipAddress + " "
							+ port);

					sendStream = rtpMgrs[i].createSendStream(dataOutput, i);
					sendStream.start();
				} catch (Exception e) {
					return e.getMessage();
				}
			}

			return null;
		}

		/****************************************************************
		 * Convenience methods to handle processor's state changes.
		 ****************************************************************/

		private Integer stateLock = new Integer(0);
		private boolean failed = false;

		Integer getStateLock() {
			return stateLock;
		}

		void setFailed() {
			failed = true;
		}

		private synchronized boolean waitForState(Processor p, int state) {
			p.addControllerListener(new StateListener());
			failed = false;

			// Call the required method on the processor
			if (state == Processor.Configured) {
				p.configure();
			} else if (state == Processor.Realized) {
				p.realize();
			}

			// Wait until we get an event that confirms the
			// success of the method, or a failure event.
			// See StateListener inner class
			while (p.getState() < state && !failed) {
				synchronized (getStateLock()) {
					try {
						getStateLock().wait();
					} catch (InterruptedException ie) {
						return false;
					}
				}
			}

			if (failed)
				return false;
			else
				return true;
		}

		/****************************************************************
		 * Inner Classes
		 ****************************************************************/

		class StateListener implements ControllerListener {

			public void controllerUpdate(ControllerEvent ce) {

				// If there was an error during configure or
				// realize, the processor will be closed
				if (ce instanceof ControllerClosedEvent)
					setFailed();

				// All controller events, send a notification
				// to the waiting thread in waitForState method.
				if (ce instanceof ControllerEvent) {
					synchronized (getStateLock()) {
						getStateLock().notifyAll();
					}
				}
			}
		}
	}
}