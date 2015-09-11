/**
* Global Sensor Networks (GSN) Source Code
* Copyright (c) 2006-2014, Ecole Polytechnique Federale de Lausanne (EPFL)
*
* This file is part of GSN.
*
* GSN is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* GSN is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with GSN. If not, see <http://www.gnu.org/licenses/>.
*
* File: gsn-tiny/src/tinygsn/model/vsensor/AbstractVirtualSensor.java
*
* @author Do Ngoc Hoan
*/


package tinygsn.model.vsensor;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;

import tinygsn.beans.DataField;
import tinygsn.beans.InputStream;
import tinygsn.beans.StaticData;
import tinygsn.beans.StreamElement;
import tinygsn.beans.StreamSource;
import tinygsn.beans.VSensorConfig;
import tinygsn.storage.db.SqliteStorageManager;

public abstract class AbstractVirtualSensor implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -94046553047097162L;
	public static final String[] VIRTUAL_SENSOR_LIST = { "bridge", "notification", "activity","MET compute", "O3 calibrate","Exposure" };
	public static final String[] VIRTUAL_SENSOR_CLASSES = {"tinygsn.model.vsensor.BridgeVirtualSensor","tinygsn.model.vsensor.NotificationVirtualSensor",
		"tinygsn.model.vsensor.ActivityVirtualSensor","tinygsn.model.vsensor.METVirtualSensor","tinygsn.model.vsensor.CalibrateOzoneVirtualSensor",
		"tinygsn.model.vsensor.ExposureVirtualSensor"};

	private transient SqliteStorageManager storage = new SqliteStorageManager();
	private VSensorConfig config;
	public InputStream is;
	
	
	
	public boolean initialize_wrapper(){
		HashMap<String,String> param = storage.getSetting("vsensor:"+config.getName()+":");
		for(Entry<String,String> e : param.entrySet()){
			initParameter(e.getKey(), e.getValue());
		}
		return initialize();
	}
	
	
	public String[] getParameters(){return new String[]{};}

	public abstract boolean initialize();
	
	protected void initParameter(String key, String value){}

	// synchronized

	protected void dataProduced(StreamElement streamElement, boolean adjust) {

		try {
			storage.executeInsert("vs_" + config.getName(), null, streamElement);
		}
		catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Calls the dataProduced with adjust = false.
	 * 
	 * @param streamElement
	 */
	protected synchronized void dataProduced(StreamElement streamElement) {
		dataProduced(streamElement, true);
	}

	/**
	 * First checks compatibility of the data type of each output data item in the
	 * stream element with the defined output in the VSD file. (this check is done
	 * regardless of the value for adjust flag).
	 * <p>
	 * If the adjust flag is set to true, the method checks the newly generated
	 * stream element and returns true if and only if the number of data items is
	 * equal to the number of output data structure defined for this virtual
	 * sensor. If the adjust=true, then this test is not performed.
	 * 
	 * @param se
	 * @param outputStructure
	 * @param adjust
	 *          default is false.
	 * @return
	 */
	/*private static boolean compatibleStructure(StreamElement se,
			DataField[] outputStructure, boolean adjust) {
		// if (!adjust && outputStructure.length != se.getFieldNames().length ) {
		// logger.warn(
		// "Validation problem, the number of field doesn't match the number of output data strcture of the virtual sensor"
		// );
		// return false;
		// }
		// int i =-1;
		// for (DataField field: outputStructure) {
		// Serializable value = se.getData(field.getName());
		// i++;
		// if (value==null)
		// continue;
		// if ( ( ( field.getDataTypeID() == DataTypes.BIGINT ||
		// field.getDataTypeID() == DataTypes.DOUBLE ||
		// field.getDataTypeID() == DataTypes.INTEGER||
		// field.getDataTypeID() == DataTypes.SMALLINT||
		// field.getDataTypeID() == DataTypes.TINYINT ) &&!(value instanceof
		// Number))
		// ||
		// ( (field.getDataTypeID() == DataTypes.VARCHAR || field.getDataTypeID() ==
		// DataTypes.CHAR) && !(value instanceof String)) ||
		// ( (field.getDataTypeID() == DataTypes.BINARY) && !(value instanceof
		// byte[]))
		// ){
		// logger.warn( "Validation problem for output field >" + field.getName( ) +
		// ", The field type declared as >" +
		// field.getType()+"< while in VSD it is defined as >"+DataTypes.TYPE_NAMES[outputStructure[
		// i ].getDataTypeID( )]);
		// return false;
		// }
		// }
		return true;
	}*/
	
	public DataField[] getOutputStructure(DataField[] in){
		return in;
	}

	/**
	 * Called when the container want to stop the pool and remove it's resources.
	 * The container will call this method once on each install of the virtual
	 * sensor in the pool. The progrmmer should release all the resouce used by
	 * this virtual sensor instance in this method specially those resouces
	 * aquired during the <code>initialize</code> call.
	 * <p/>
	 * Called once while finalizing an instance of the virtual sensor
	 */
	public abstract void dispose();

	
	/**
	 * @return the virtualSensorConfiguration
	 */
	public VSensorConfig getVirtualSensorConfiguration() {
		if (config == null) {
			throw new RuntimeException("The VirtualSensorParameter is not set !!!");
		}
		return config;
	}

	/**
	 * @param virtualSensorConfiguration
	 *          the virtualSensorConfiguration to set
	 */
	public void setVirtualSensorConfiguration(
			VSensorConfig virtualSensorConfiguration) {
		this.config = virtualSensorConfiguration;
	}

	/**
	 * This method is going to be called by the container when one of the input
	 * streams has a data to be delivered to this virtual sensor. After receiving
	 * the data, the virtual sensor can do the processing on it and this
	 * processing could possibly result in producing a new stream element in this
	 * virtual sensor in which case the virtual sensor will notify the container
	 * by simply adding itself to the list of the virtual sensors which have
	 * produced data. (calling <code>container.publishData(this)</code>. For more
	 * information please check the <code>AbstractVirtalSensor</code>
	 * 
	 * @param inputStreamName
	 *          is the name of the input stream as specified in the configuration
	 *          file of the virtual sensor.
	 * @param streamElement
	 *          is actually the real data which is produced by the input stream
	 *          and should be delivered to the virtual sensor for possible
	 *          processing.
	 */
	public abstract void dataAvailable(String inputStreamName,
			StreamElement streamElement);
	
	public void dataAvailable(String inputStreamName,
			ArrayList<StreamElement> data){
		if (!data.isEmpty()) dataAvailable(inputStreamName,data.get(data.size()-1));
	}
	
	synchronized public void start() {
		config = StaticData.findConfig(config.getId());
		if (!config.getRunning()){
			config.setRunning(true);
		}
	}

	synchronized public void stop() {
		config = StaticData.findConfig(config.getId());
		if (config.getRunning()){
			config.setRunning(false);
		}
		
	}

	public VSensorConfig getConfig() {
		return config;
	}
	
	public void delete(){
		stop();
		for (StreamSource streamSource : config.getInputStream().getSources()) {
			streamSource.dispose();
		}
		dispose();
	}

}