package gsn.acquisition2.wrappers;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import org.apache.log4j.Logger;
import gsn.acquisition2.messages.DataMsg;
import gsn.beans.DataField;

public class MigMessageWrapperProcessor extends SafeStorageAbstractWrapper {

	private MigMessageParameters parameters = null;

	private Class<?> classTemplate = null;

	private Constructor<?> messageConstructor = null;

	private final transient Logger logger = Logger.getLogger( MigMessageWrapperProcessor.class );

	public boolean initialize() {
		logger.warn("tinyos processor wrapper initialize started...");
		if (! super.initialize()) return false; 
		try {
			parameters = new MigMessageParameters () ;
			parameters.initParameters(getActiveAddressBean());			
			//
			classTemplate = Class.forName(parameters.getTinyosMessageName());
			parameters.buildOutputStructure(classTemplate, new ArrayList<DataField>(), new ArrayList<Method>());
			//
			messageConstructor = classTemplate.getConstructor(byte[].class) ;			
		}
		catch (RuntimeException e) {
			logger.error(e.getMessage());
			return false;
		} catch (ClassNotFoundException e) {
			logger.error("Unable to find the >" + parameters.getTinyosMessageName() + "< class.");
			return false;
		} catch (NoSuchMethodException e) {
			logger.error("Unable to find the >" + parameters.getTinyosMessageName() + "(byte[]) constructor.<");
			return false;
		}
		logger.warn("tinyos processor wrapper initialize completed...");
		return true;
	}

	@Override
	public boolean messageToBeProcessed(DataMsg dataMessage) {

		Method getter = null;
		Object res = null;
		Serializable resarray = null;
		byte[] rawmsg = (byte[]) dataMessage.getData()[0];
		
		try {
			
			if (logger.isDebugEnabled()) {
				StringBuilder rawmsgoutput = new StringBuilder ();
				for (int i = 0 ; i < rawmsg.length ; i++) {
					rawmsgoutput.append(rawmsg[i]);
				}
				logger.debug("new message to be processed: " + rawmsgoutput.toString());
			}
			

			Object msg = (Object) messageConstructor.newInstance(rawmsg);

			ArrayList<Serializable> output = new ArrayList<Serializable> () ;
			Iterator<Method> iter = parameters.getGetters().iterator();
			while (iter.hasNext()) {
				getter = (Method) iter.next();
				getter.setAccessible(true);
				res = getter.invoke(msg);
				if (getter.getReturnType().isArray()) {
					for(int i = 0 ; i < Array.getLength(res) ; i++) {
						resarray = (Serializable) Array.get(res, i);
						output.add(resarray);
						logger.debug("> " + getter.getName() + ": " + resarray);
					}
				}
				else {
					output.add((Serializable)res);
					logger.debug("> " + getter.getName() + ": " + res);
				}
			}

			// Update TIMED field
			if (parameters.getTimedFieldGetter() != null) {
				parameters.getTimedFieldGetter().setAccessible(true);
				Long ts = (Long) parameters.getTimedFieldGetter().invoke(msg);
				postStreamElement(ts.longValue(), output.toArray(new Serializable[] {}));
			}
			else {
				postStreamElement(output.toArray(new Serializable[] {}));
			}
		} catch (InstantiationException e) {
			logger.error("Unable to instanciate the message");
		} catch (IllegalAccessException e) {
			logger.error("Illegal Access to >" + getter + "<");
		} catch (IllegalArgumentException e) {
			logger.error("Illegal argument to >" + getter + "<");
		} catch (InvocationTargetException e) {
			logger.error("Invocation Target Exception " + e.getMessage());
		} catch (SecurityException e) {
			logger.error("Security Exception " + e.getMessage());
		}
		return true;
	}

	@Override
	public DataField[] getOutputFormat() {
		return parameters.getOutputStructure() ;
	}

	@Override
	public boolean isTimeStampUnique() {
		return false;
	}
}
