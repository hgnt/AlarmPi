package alarmpi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * handler class for TCP client connections
 */
public class TcpRequestHandler implements Runnable {

	public TcpRequestHandler(Controller controller,Socket socket) {
		this.controller    = controller;
		this.clientSocket  = socket;
		
		// create list with all command handlers
		commandHandlerList = new LinkedList<CommandHandler>();
		commandHandlerList.add(new CommandLoglevel());
		commandHandlerList.add(new CommandSound());
		commandHandlerList.add(new CommandLightControlOld());
		commandHandlerList.add(new CommandLightControl());
		commandHandlerList.add(new CommandTimer());
		commandHandlerList.add(new CommandCalendar());
	}

	@Override
	public void run() {
		final int BUFFER_SIZE = 100; // size of command buffer
		log.info("client connected from "+clientSocket.getRemoteSocketAddress());

		try {
			boolean exit = false;
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			char[] buffer = new char[BUFFER_SIZE];
			clientStream  = new PrintWriter(clientSocket.getOutputStream(), true);
			
			clientStream.println("connected to AlarmPi");

			// read and process client commands until 'exit' command is received
			while (!exit) {
				clientStream.print("command: ");
				clientStream.flush();
				
				int length = bufferedReader.read(buffer, 0, BUFFER_SIZE);
				if (length <= 0) {
					log.info("client connection terminated");
					exit = true;
				} else if (length == BUFFER_SIZE) {
					log.severe("message from client exceeds max. length of "
							+ BUFFER_SIZE + ". Ignoring...");
				} else {
					String message = new String(buffer, 0, length).trim();
					log.fine("received message from client: " + message);

					// separate command from parameters
					int index = message.indexOf(' ');
					if (index < 0 || index==message.length()-1) {
						// no spaces found - treat complete message as command
						command    = message.toLowerCase();
						parameters = null;
					} else {
						command    = message.substring(0, index).toLowerCase();
						parameters = message.substring(index+1).split(" ");
					}
					log.fine("received command: >>"+command+"<<");
					boolean isQuery = command.endsWith("?");
					if(isQuery) {
						command = command.substring(0, command.length()-1).toLowerCase();
					}

					// process command
					if (command.equals("exit")) {
						clientSocket.close();
					}
					else if (command.equals("help")) {
						String answer = new String("available commands:\n");
						for(CommandHandler handler:commandHandlerList) {
							answer += "  "+handler.getCommandName()+"\n";
						}
						clientStream.println(new ReturnCodeSuccess(answer));
						clientStream.flush();
					}
					else {
						Boolean processed = false;
						Iterator<CommandHandler> it = commandHandlerList.iterator();
						while(it.hasNext()) {
							CommandHandler handler = it.next();
							if(handler.getCommandName().equalsIgnoreCase(command)) {
								log.fine("command match found: "+handler.getCommandName());
								try {
									ReturnCode rc = handler.process(isQuery);
									log.fine("sending answer: >>"+rc+"<<");
									clientStream.println(rc);
									clientStream.flush();
									processed = true;
									break;
								} catch (CommandHandlerException e) {
									clientStream.println(new ReturnCodeError(e.getMessage()));
									clientStream.flush();
									log.info("command execution failed. message was: "+message);
								}
							}
						}
						
						if(!processed) {
							clientStream.println(new ReturnCodeError("Unknown command "+command));
							clientStream.flush();
							log.info("received unknown command: "+command);
						}
					}
				}
			}
		} catch (IOException e) {
			log.info(e.getMessage());
		}
	}
	
	//
	// local private classes for handling of the individual commands
	//
	
	// Exception class - used for all errors
	private class CommandHandlerException extends Exception {
		private static final long serialVersionUID = 5687868525223365791L;
	}
	
	// return code for process method
	private abstract class ReturnCode {
		public ReturnCode(String message) {
			this.message = message;
		}
		
		protected String  message;
	}
	
	private class ReturnCodeSuccess extends ReturnCode {
		public ReturnCodeSuccess() {
			super("");
		}
		public ReturnCodeSuccess(String message) {
			super(message);
		}
		@Override
		public String toString() {
			return "OK\n"+message;		}
	}
	
	private class ReturnCodeError extends ReturnCode {
		public ReturnCodeError(String message) {
			super(message);
		}
		@Override
		public String toString() {
			return "ERROR\n"+message;
		}
	}

	// abstract base class for command handlers
	private abstract class CommandHandler {
		
		public ReturnCode process(final boolean isQuery) throws CommandHandlerException {
			
			if(isQuery) {
				log.finest("calling get for command "+getCommandName());
				return get();
			}
			else {
				log.finest("calling set for command "+getCommandName());
				return set();
			}
		}
		
		protected abstract ReturnCode set() throws CommandHandlerException;
		
		protected abstract ReturnCode get() throws CommandHandlerException;
		
		protected abstract String getCommandName();
	};
	


	/**
	 * loglevel - log level
	 * sets or queries the current log level
	 * syntax   : loglevel <level>
	 * parameter: <level>  new java log level (warning,info,...)
	 */
	private class CommandLoglevel extends CommandHandler {
		
		@Override
		protected String getCommandName() {return "loglevel";}
		
		@Override
		public ReturnCode set() throws CommandHandlerException {
			try {
				LogManager.getLogManager().getLogger("alarmpi").setLevel(Level.parse(parameters[0].toUpperCase()));
				return new ReturnCodeSuccess();
			}
			catch(IllegalArgumentException e) {
				return new ReturnCodeError("invalid log level "+parameters[0]);
			}
		}
		
		@Override
		public ReturnCode get() throws CommandHandlerException{
			return new ReturnCodeSuccess(LogManager.getLogManager().getLogger("alarmpi").getLevel().toString());
		}
	};
	

	
	/**
	 * sound - controls sound
	 */
	
	private class CommandSound extends CommandHandler{

		@Override
		protected String getCommandName() {
			return "sound";
		}

		@Override
		public ReturnCode set() throws CommandHandlerException{
			if(parameters==null || parameters.length<1) {
				return new ReturnCodeError("sound: missing command (off | play | volume | timer)");
			}
			
			if(parameters[0].equals("on")) {
				return on();
			}
			else if(parameters[0].equals("off")) {
				return off();
			}
			else if(parameters[0].equals("play")) {
				return play();
			}
			else if(parameters[0].equals("volume")) {
				return volume();
			}
			else if(parameters[0].equals("timer")) {
				return timer();
			}
			else {
				return new ReturnCodeError("unknown sound command: "+parameters[0]);
			}
		}
		
		@Override
		protected ReturnCode get() throws CommandHandlerException {
			String answer = new String();
			
			SoundControl soundControl = SoundControl.getSoundControl();
			answer = String.format("%d %d %d\n", -1,soundControl.getVolume(),controller.getSoundTimer());
			for(Alarm.Sound sound:Configuration.getConfiguration().getSoundList()) {
				answer += String.format("%s %s\n", sound.name,sound.type);
			}
			return new ReturnCodeSuccess(answer);
		}
		
		// individual set commands
		private ReturnCode on() {
			SoundControl.getSoundControl().on();
			return new ReturnCodeSuccess("");
		}
		
		// stop
		private ReturnCode off() {
			SoundControl.getSoundControl().off();
			return new ReturnCodeSuccess("");
		}
		
		// play
		private ReturnCode play() {
			if(parameters.length<2) {
				return new ReturnCodeError("sound play: sound ID missing");
			}
			SoundControl.getSoundControl().on();
			try {
				SoundControl.getSoundControl().playSound(Configuration.getConfiguration().getSoundList().get(Integer.parseInt(parameters[1])),null,false);
				return new ReturnCodeSuccess("");
			}
			catch(NumberFormatException | IndexOutOfBoundsException e) {
				return new ReturnCodeError("sound play: invalid sound ID");
			}
		}
		
		// volume
		private ReturnCode volume() {
			if(parameters.length<2) {
				return new ReturnCodeError("sound volume: missing volume (0...100)");
			}
			int volume = Integer.parseInt(parameters[1]);
			if(volume<0 || volume>100) {
				return new ReturnCodeError("sound volume: out of range (0...100)");
			}
			SoundControl.getSoundControl().setVolume(volume);
			return new ReturnCodeSuccess("");
		}
		
		// timer
		private ReturnCode timer() {
			if(parameters.length<2) {
				return new ReturnCodeError("sound timer: missing timer value");
			}
			if(parameters[1].equalsIgnoreCase("off")) {
				controller.deleteSoundTimer();
			}
			else {
				try {
					int timer = Integer.parseInt(parameters[1]);
					controller.setSoundTimer(timer);
				}
				catch(NumberFormatException e) {
					return new ReturnCodeError("sound timer: invalid timer value");
				}
			}
			return new ReturnCodeSuccess("");
		}
	};
	
	private class CommandLightControlOld extends CommandHandler{

		@Override
		protected String getCommandName() {
			return "light";
		}

		@Override
		public ReturnCode set() throws CommandHandlerException{
//			if(parameters==null || parameters.length!=1) {
//				return new ReturnCodeError("light: invalid parameter count ("+parameters.length+"). Syntax: light <pwm value>");
//			}
//			
//			if(parameters[0].equalsIgnoreCase("off")) {
//				controller.getLightControl().off();
//			}
//			else if(parameters[0].equalsIgnoreCase("dim")) {
//				controller.getLightControl().dimUp(100, 600);
//			}
//			else {
//				try {
//					int percentage = Integer.parseInt(parameters[0]);
//					if(percentage>=0) {
//						controller.getLightControl().setBrightness(percentage);
//					}
//					else {
//						// debugging only. If number is negative, set raw PWM value
//						controller.getLightControl().setPwm(-percentage);
//					}
//				} catch(NumberFormatException e) {
//					return new ReturnCodeError("unable to parse light pwm percentage value");
//				}
//			}
			
			return new ReturnCodeSuccess();
		}
		
		@Override
		protected ReturnCode get() throws CommandHandlerException {
			return new ReturnCodeSuccess();
			//return new ReturnCodeSuccess(String.valueOf(Math.round(controller.getLightControl().getBrightness())));
		}
	};
	
	private class CommandLightControl extends CommandHandler{

		@Override
		protected String getCommandName() {
			return "lights";
		}

		@Override
		public ReturnCode set() throws CommandHandlerException{
			if(parameters==null ) {
				return new ReturnCodeError("light: paramaters is null");
			}
			
			if(parameters.length!=2) {
				return new ReturnCodeError("light: invalid parameter count ("+parameters.length+"). Syntax: lights <id> <brightness in percent>");
			}
			
			int id = Integer.parseInt(parameters[0]);
			List<LightControl> lightControls = controller.getLightControlList();
			
			if(parameters[1].equalsIgnoreCase("off")) {
				lightControls.stream().filter(light->light.getId()==id).forEach(light->light.setOff());
			}
			else if(parameters[1].equalsIgnoreCase("dim")) {
				lightControls.stream().filter(light->light.getId()==id).forEach(light->light.dimUp(100, 600));
			}
			else {
				try {
					int percentage = Integer.parseInt(parameters[1]);
					if(percentage>=0) {
						lightControls.stream().filter(light->light.getId()==id).forEach(light->light.setBrightness(percentage));
					}
					else {
						// debugging only. If number is negative, set raw PWM value
						lightControls.stream().filter(light->light.getId()==id).forEach(light->light.setPwm(-percentage));
					}
				} catch(NumberFormatException e) {
					return new ReturnCodeError("unable to parse light pwm percentage value");
				}
			}
			
			return new ReturnCodeSuccess();
		}
		
		@Override
		protected ReturnCode get() throws CommandHandlerException {
			List<LightControl> lightControls = controller.getLightControlList();
			String answer = lightControls.size()+" ";
			for(LightControl lightControl:lightControls) {
				answer += String.valueOf(Math.round(lightControl.getBrightness())+" ");
			}

			return new ReturnCodeSuccess(answer);
		}
	};
	
	
	private class CommandTimer extends CommandHandler{

		@Override
		protected String getCommandName() {
			return "timer";
		}

		@Override
		public ReturnCode set() throws CommandHandlerException{
			if(parameters==null) {
				return new ReturnCodeError("led: parameters is null");
			}
			if(parameters.length!=1) {
				return new ReturnCodeError("led: invalid parameter count ("+parameters.length+"). Syntax: timer off | <seconds from now>");
			}
			
			if(parameters[0].equalsIgnoreCase("off")) {
				controller.deleteSoundTimer();
			}
			else {
				try {
					int secondsFromNow = Integer.parseInt(parameters[0]);
					controller.setSoundTimer(secondsFromNow);
				} catch(NumberFormatException e) {
					return new ReturnCodeError("unable to parse timer seconds value");
				}
			}
			
			return new ReturnCodeSuccess();
		}
		
		@Override
		protected ReturnCode get() throws CommandHandlerException {
			return new ReturnCodeSuccess(String.valueOf(controller.getSoundTimer()));
		}
	};
	
	
	/**
	 * Deals with Google Calendar
	 */
	private class CommandCalendar extends CommandHandler {
		
		@Override
		protected String getCommandName() {return "calendar";}
		
		@Override
		public ReturnCode set() throws CommandHandlerException {
			if(parameters==null ) {
				return new ReturnCodeError("calendar: parameters is null");
			}
			if(parameters.length>2) {
				return new ReturnCodeError("calendar: invalid parameter count ("+parameters.length+").");
			}
			
			if(parameters[0].equalsIgnoreCase("geturl")) {
				if(calendar==null) {
					calendar = new GoogleCalendar();
				}
				url = calendar.getAuthorizationUrl();
				if(url==null) {
					return new ReturnCodeError("Already authorized");
				}
				return new ReturnCodeSuccess(url);
			}
			else if(parameters[0].equalsIgnoreCase("setcode")) {
				if(url==null) {
					return new ReturnCodeError("Already authorized or geturl not called");
				}
				if(parameters.length!=2) {
					return new ReturnCodeError("usage: setcode <code>");
				}
				if(calendar.setAuthorizationCode(parameters[1])) {
					return new ReturnCodeSuccess("Authorization successfull.");
				}
				else {
					return new ReturnCodeError("Authorization failed. Please refer to logfiles for details.");
				}
			}
			
			return new ReturnCodeError("invalid command: "+parameters[0]);
		}
		
		@Override
		public ReturnCode get() throws CommandHandlerException{
			if(calendar==null) {
				calendar = new GoogleCalendar();
				if(!calendar.connect()) {
					return new ReturnCodeError("Unable to read calendar");
				}
			}
			List<String> entries = calendar.getCalendarEntries(GoogleCalendar.Mode.TODAY);
			String answer = new String();
			for(String entry:entries) {
				answer += entry+"\n";
			}
			return new ReturnCodeSuccess(answer);
		}
		
		private GoogleCalendar calendar = null;
		private String         url      = null;
	};
	
	//
	// private data members
	//
	private static final Logger log = Logger.getLogger( TcpRequestHandler.class.getName() );
	private final Socket               clientSocket;
	private final Controller           controller;
	private       PrintWriter          clientStream;
	private       String               command;
	private       String[]             parameters;
	private final List<CommandHandler> commandHandlerList;
	final ExecutorService threadPool = Executors.newCachedThreadPool();
}
