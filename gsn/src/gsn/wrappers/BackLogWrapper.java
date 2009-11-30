package gsn.wrappers;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.naming.OperationNotSupportedException;

import org.apache.log4j.Logger;

import gsn.wrappers.backlog.BackLogMessage;
import gsn.wrappers.backlog.DeploymentClient;
import gsn.wrappers.backlog.BackLogMessageListener;
import gsn.wrappers.backlog.plugins.AbstractPlugin;
import gsn.wrappers.backlog.sf.SFListen;
import gsn.wrappers.backlog.sf.SFv1Listen;
import gsn.beans.AddressBean;
import gsn.beans.DataField;


/**
 * Offers the main backlog functionality needed on the GSN side.
 * <p>
 * First the BackLogWrapper starts a plugin (specified in the
 * virtual sensor's XML file under 'plugin-classname') which
 * offers the functionality to process a specific backlog
 * message type, e.g. MIG messages.
 * <p>
 * Second, the BackLogWrapper starts or reuses a
 * {@link DeploymentClient} which opens a connection to the
 * deployment address specified in the virtual sensor's XML file
 * under 'deployment'. It then registers itself to listen to the
 * DeploymentClient for the backlog message type specified in the
 * used plugin.
 * <p>
 * If the optional parameter 'local-sf-port' is specified in the
 * virtual sensor's XML file, the BackLogWrapper starts a local
 * serial forwarder ({@link SFListen}) and registers it to listen
 * for the MIG {@link BackLogMessage} type at the
 * DeploymentClient. Thus, all incoming MIG packets will
 * be forwarded to any SF listener. This guarantees any SF
 * listeners to receive all MIG packets produced at the deployment,
 * as it passively benefits from the backlog functionality.
 *
 * @author	Tonio Gsell
 * 
 * @see BackLogMessage
 * @see AbstractPlugin
 * @see DeploymentClient
 * @see SFListen
 */
public class BackLogWrapper extends AbstractWrapper implements BackLogMessageListener {

	// Mandatory Parameter
	/**
	 * The field name for the deployment as used in the virtual sensor's XML file.
	 */
	private static final String BACKLOG_PLUGIN = "plugin-classname";
	private static final String SF_LOCAL_PORT = "local-sf-port";
	private static final String TINYOS1X_PLATFORM = "tinyos1x-platform";
	
	private String deployment = null;
	private static Map<String,Properties> propertyList = new HashMap<String,Properties>();
	private String plugin = null;
	private AbstractPlugin pluginObject = null;
	private AddressBean addressBean = null;
	private static Map<String,DeploymentClient> deploymentClientList = new HashMap<String,DeploymentClient>();
	private DeploymentClient deploymentClient = null;
	private static Map<String,SFListen> sfListenList = new HashMap<String,SFListen>();
	private static Map<String,SFv1Listen> sfv1ListenList = new HashMap<String,SFv1Listen>();
	private SFListen sfListen = null;
	private SFv1Listen sfv1Listen = null;
	private static Map<String,Integer> activePluginsCounterList = new HashMap<String,Integer>();
	private String tinyos1x_platform = null;
	
	private final transient Logger logger = Logger.getLogger( BackLogWrapper.class );



	/**
	 * Initializes the BackLogWrapper. This function is called by GSN.
	 * <p>
	 * Checks the virtual sensor's XML file for the availability of
	 * 'deployment' and 'plugin-classname' fields.
	 * 
	 * Instantiates and initializes the specified plugin.
	 * 
	 * Starts or reuses a DeploymentClient and registers itself to
	 * it as listener for the BackLogMessage type specified by the
	 * used plugin.
	 * 
	 * If the optional parameter 'local-sf-port' is specified in the
	 * virtual sensor's XML file, the BackLogWrapper starts a local
	 * serial forwarder (SFListen) and registers it to listen for the
	 * MIG BackLogMessage type at the DeploymentClient. Thus, all
	 * incoming MIG packets will be forwarded to any SF listener.
	 * 
	 * @return true if the initialization was successful otherwise
	 * 			 false.
	 */
	@Override
	public boolean initialize() {
		Properties props;
		
		logger.debug("BackLog wrapper initialize started...");
		
	    addressBean = getActiveAddressBean();

		deployment = addressBean.getVirtualSensorName().split("_")[0].toLowerCase();
		
		synchronized (propertyList) {
			if (propertyList.containsKey(deployment)) {
				props = propertyList.get(deployment);
			} else {
				String propertyfile = "conf/backlog/" + deployment + ".properties";
				try {
					props = new Properties();
					props.load(new FileInputStream(propertyfile));
					propertyList.put(deployment, props);
				} catch (Exception e) {
					logger.error("Could not load property file: " + propertyfile, e);
					return false;
				}
			}
		}
		
		String address = props.getProperty("address");
		if (address == null) {
			logger.error("Could not get property 'address' from property file");
			return false;
		}
		
		plugin = addressBean.getPredicateValue(BACKLOG_PLUGIN);
		
	    // check the XML file for the plugin entry
		if (plugin == null) {
			logger.error("Loading the PSBackLog wrapper failed due to missing >" + BACKLOG_PLUGIN + "< predicate.");
			return false;
	    }
		
		// instantiating the plugin class specified in the XML file
		logger.debug("Loading BackLog plugin: >" + plugin + "<");
		try {
			Class<?> cl = Class.forName(plugin);
			pluginObject = (AbstractPlugin) cl.getConstructor().newInstance();
		} catch (ClassNotFoundException e) {
			logger.error("The plugin class >" + plugin + "< could not be found");
			return false;
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return false;
		}
		
		
		// initializing the plugin
        if( !pluginObject.initialize(this) ) {
    		logger.error("Could not load BackLog plugin: >" + plugin + "<");
        	return false;
        }
		
		// start or reuse a deployment client
		synchronized (deploymentClientList) {
			if (deploymentClientList.containsKey(deployment)) {
				logger.info("Reusing existing DeploymentClient for deployment: >" + deployment + "<");
				deploymentClient = deploymentClientList.get(deployment);
				deploymentClient.registerListener(pluginObject.getMessageType(), this);
			}
			else {
				try {
					logger.info("Loading DeploymentClient for deployment: >" + deployment + "<");
					deploymentClient = new DeploymentClient(address);
					deploymentClient.registerListener(pluginObject.getMessageType(), this);
					deploymentClient.start();					
					deploymentClientList.put(deployment, deploymentClient);
				} catch (Exception e) {
					logger.error("Could not load DeploymentClient for deployment: >" + deployment + "<");
					return false;
				}
			}
		}
		
		// count active plugins per deployment
		synchronized (activePluginsCounterList) {
			if (activePluginsCounterList.containsKey(deployment)) {
				int n = activePluginsCounterList.get(deployment);
				activePluginsCounterList.put(deployment, n+1);
			}
			else {
				activePluginsCounterList.put(deployment, 1);
			}
		}
		
		tinyos1x_platform = props.getProperty(TINYOS1X_PLATFORM);
		String sflocalport = props.getProperty(SF_LOCAL_PORT);
		
		// start optional local serial forwarder
		if (tinyos1x_platform == null) {
			synchronized (sfListenList) {
				if (sfListenList.containsKey(deployment)) {
					sfListen = sfListenList.get(deployment);
				}
				else {
					if (sflocalport != null) {
						int port = -1;
						try {
							port = Integer.parseInt(sflocalport);
							sfListen = new SFListen(port, deploymentClient);
							logger.info("starting local serial forwarder on port " + port + " for deployment: >" + deployment + "<");
							sfListen.start();
							sfListenList.put(deployment, sfListen);
						} catch (Exception e) {
							logger.error("Could not start serial forwarder on port " + port + " for deployment: >" + deployment + "<");							
						}
					}
				}
			}
		} else {
			synchronized (sfv1ListenList) {
				if (sfv1ListenList.containsKey(deployment)) {
					sfv1Listen = sfv1ListenList.get(deployment);
				}
				else {
					if (sflocalport != null) {
						int port = -1;
						try {
							port = Integer.parseInt(sflocalport);
							sfv1Listen = new SFv1Listen(port, deploymentClient, tinyos1x_platform);
							logger.info("starting local serial forwarder 1.x on port " + port + " for deployment: >" + deployment + "<");
							sfv1Listen.start();
							sfv1ListenList.put(deployment, sfv1Listen);
						} catch (Exception e) {
							logger.error("Could not start serial forwarder 1.x on port " + port + " for deployment: >" + deployment + "<");							
						}
					}
				}
			}
		}
		
		return true;
	}




	/**
	 * This function can be called by the plugin, if it has processed
	 * the data. The data will be forwarded to the corresponding
	 * virtual sensor by GSN and will be put into the database.
	 * <p>
	 * The data format must correspond to the one specified by
	 * the plugin's getOutputFormat() function.
	 * 
	 * @param timestamp
	 * 			The timestamp in milliseconds this data has been
	 * 			generated.
	 * @param data 
	 * 			The data to be processed. Its format must correspond
	 * 			to the one specified by the plugin's getOutputFormat()
	 * 			function.
	 * @return false if storing the new item fails otherwise true
	 */
	public boolean dataProcessed(long timestamp, Serializable... data) {
		logger.debug("dataProcessed timestamp: " + timestamp);
		return postStreamElement(timestamp, data);
	}




	/**
	 * This function can be called by the plugin, if it has processed
	 * the data. Thus, the data will be forwarded to the corresponding
	 * virtual sensor by GSN.
	 * 
	 * 
	 * @param timestamp
	 * 			The timestamp in milliseconds this data has been
	 * 			generated.
	 * @param data 
	 * 			The data to be processed. Its format must correspond
	 * 			to the one specified by the plugin's getOutputFormat()
	 * 			function.
	 * @return if the message has been sent successfully true will be returned
	 * 			 else false (no working connection)
	 */
	public boolean sendRemote(byte[] data) {
		try {
			return deploymentClient.sendMessage(new BackLogMessage(pluginObject.getMessageType(), System.currentTimeMillis(), data));
		} catch (IOException e) {
			logger.error(e.getMessage());
			return false;
		}
	}




	/**
	 * This function must be called by the plugin, to acknowledge
	 * incoming messages if it is using the backlog functionality
	 * on the deployment side.
	 * 
	 * The timestamp will be used at the deployment to remove the
	 * corresponding message backloged in the database.
	 * 
	 * @param timestamp
	 * 			The timestamp is used to acknowledge a message. Thus
	 * 			it has to be equal to the timestamp from the received
	 * 			message we want to acknowledge.
	 */
	public void ackMessage(long timestamp) {
		deploymentClient.sendAck(timestamp);
	}

	
	
	
	/**
	 * Retruns true if the deploymentClient is connected to the deployment.
	 * 
	 * @return true if the client is connected otherwise false
	 */
	public boolean isConnected() {
		if( deploymentClient != null )
			return deploymentClient.isConnected();
		else
			return false;
	}





	/**
	 * Returns the output format specified by the used plugin.
	 * 
	 * This function is needed by GSN.
	 * 
	 * @return the output format for the used plugin.
	 */
	@Override
	public DataField[] getOutputFormat() {
		return pluginObject.getOutputFormat();
	}





	/**
	 * Returns the name of this wrapper.
	 * 
	 * This function is needed by GSN.
	 * 
	 * @return the name of this wrapper.
	 */
	@Override
	public String getWrapperName() {
		return "BackLogWrapper";
	}




	/**
	 * This function can be only called by a virtual sensor to send
	 * a command to the plugin.
	 * <p>
	 * The code in the virtual sensor's class would be something like:
	 * <p>
	 * <ul>
	 *  {@code vsensor.getInputStream( INPUT_STREAM_NAME ).getSource( STREAM_SOURCE_ALIAS_NAME ).getWrapper( ).sendToWrapper(command, paramNames, paramValues)}
	 * </ul>
	 * 
	 * @param action the action name
	 * @param paramNames the name of the different parameters
	 * @param paramValues the different parameter values
	 * 
	 * @return true if the plugin could successfully process the
	 * 			 data otherwise false
	 * @throws OperationNotSupportedException 
	 */
	@Override
	public boolean sendToWrapper ( String action , String [ ] paramNames , Object [ ] paramValues ) throws OperationNotSupportedException {
		logger.debug("Upload command received.");
		return pluginObject.sendToPlugin(action, paramNames, paramValues);
	}




	/**
	 * This function can be only called by a virtual sensor to send
	 * an object to the plugin.
	 * <p>
	 * The code in the virtual sensor's class would be something like:
	 * <p>
	 * <ul>
	 *  {@code vsensor.getInputStream( INPUT_STREAM_NAME ).getSource( STREAM_SOURCE_ALIAS_NAME ).getWrapper( ).sendToWrapper(dataItem)}
	 * </ul>
	 * 
	 * @param dataItem which is going to be sent to the plugin
	 * 
	 * @return true if the plugin could successfully process the
	 * 			 data otherwise false
	 * @throws OperationNotSupportedException 
	 */
	@Override
	public boolean sendToWrapper ( Object dataItem ) throws OperationNotSupportedException {
		logger.debug("Upload object received.");
		return pluginObject.sendToPlugin(dataItem);
	}
	
	
	
	@Override
	public boolean messageReceived(BackLogMessage message) {
		int packetcode = pluginObject.packetReceived(message.getTimestamp(), message.getPayload());
		if (packetcode == pluginObject.PACKET_PROCESSED)
			return true;
		else
			logger.warn("Message with timestamp >" + message.getTimestamp() + "< and type >" + message.getType() + "< could not be processed! Skip message.");
			return false;
	}

	

	/**
	 * Disposes this BackLogWrapper. This function is called by GSN.
	 * <p>
	 * Disposes the used plugin and deregisters itself from the 
	 * DeploymentClient.
	 *
	 * If this is the last BackLogWrapper for the used deployment,
	 * the DeploymentClient will be finalized, as well as an optional
	 * serial forwarder if needed.
	 */
	@Override
	public void dispose() {
		logger.info("Deregister this BackLogWrapper from the deployment >" + deployment + "<");

		deploymentClient.deregisterListener(pluginObject.getMessageType(), this);

		synchronized (activePluginsCounterList) {
			int n = activePluginsCounterList.get(deployment);
			activePluginsCounterList.put(deployment, n-1);

			if (n == 1) {
				// if this is the last listener close the serial forwarder
				if (sfListen != null) {
					sfListenList.remove(deployment);
					sfListen.interrupt();
				}
				if (sfv1Listen != null) {
					sfv1ListenList.remove(deployment);
					sfv1Listen.interrupt();
				}
				// and the client
				deploymentClientList.remove(deployment);
				deploymentClient.interrupt();

				// remove this deployment from the counter
				activePluginsCounterList.remove(deployment);

				logger.info("Final shutdown of the deployment >" + deployment + "<");
			}
		}
		
		// tell the plugin to stop
		pluginObject.stop();
	}
   
	public String getTinyos1xPlatform() {
		return tinyos1x_platform;
	}
	
	@Override
   	public boolean isTimeStampUnique() {
   		return false;
   	}

}
