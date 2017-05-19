package EZShare;

/**
 * This class is used as server side in EZShare System. The server class
 * basically takes responsibility for accepting connection with client, and
 * creates new thread for each client. You can specify a few arguments while
 * running the server, or you may use the default settings.
 * @author: Jiayu Wang
 * @date: April 5, 2017
 */

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.cli.*;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.net.ServerSocket;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import static org.apache.commons.lang.RandomStringUtils.randomAlphabetic;


public class Server {
    private final static Logger logr_info = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private final static Logger logr_debug = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    /**
     * Central storage for resource, client, server, and primary keys, shared by all functions.
     * Only exist while the server is alive.
     */
    private static HashMap<Integer, Resource> resourceList = new HashMap<>();
    private static HashMap<String, Long> clientList = new HashMap<>();
    private static JSONArray securedServerList = new JSONArray();
    private static JSONArray unsecuredServerList = new JSONArray();
    private static KeyList keys = new KeyList();

    /**
     * Default settings without command line arguments.
     */
    private static String hostname = "Dr. Stranger";
    private static int connectionSecond = 1;
    private static int exchangeSecond = 600;
    private static int port = 8080;
    private static int securedPort = 3781;  //Default Secured Port
    private static String secret;

    public static void main(String[] args) {
        try{
            setupLogger();
            logr_info.info("Starting the EZShare Server");
            Options options = new Options();
            options.addOption("advertisedhostname", true, "advertisedhostname");
            options.addOption("debug", false, "print debug information");
            options.addOption("connectionintervallimit", true, "connection interval limit in seconds");
            options.addOption("exchangeinterval", true, "exchange interval in seconds");
            options.addOption("port", true, "server port, an integer");
            options.addOption("secret", true, "secret, random string");
            options.addOption("sport", true, "secured server port, an integer");

            // Parsing command line arguments
            CommandLineParser parser = new DefaultParser();
            CommandLine cmd;
            HelpFormatter formatter = new HelpFormatter();
            try {
                cmd = parser.parse(options, args);
            } catch (ParseException e) {
                formatter.printHelp("commands", options);
                System.exit(1);
                return;
            }
            if (cmd.hasOption("advertisedhostname")) {
                hostname = cmd.getOptionValue("advertisedhostname");
            }
            if (cmd.hasOption("connectionintervallimit")) {
                try {
                    connectionSecond = Integer.parseInt(cmd.getOptionValue("connectionintervallimit"));
                } catch (NumberFormatException e) {
                    System.out.println("Please give a valid connection interval number in seconds.");
                    System.exit(1);
                }
            }
            if (cmd.hasOption("exchangeinterval")) {
                try {
                    exchangeSecond = Integer.parseInt(cmd.getOptionValue("exchangeinterval"));
                } catch (NumberFormatException e) {
                    System.out.println("Please give a valid exchange interval number in seconds.");
                    System.exit(1);
                }
            }
            if (cmd.hasOption("port")) {
                try {
                    port = Integer.parseInt(cmd.getOptionValue("port"));
                } catch (NumberFormatException e) {
                    System.out.println("Please give a valid port number." + cmd.getOptionValue("port"));
                    System.exit(1);
                }
            }
            if (cmd.hasOption("sport")) {
                try {
                    securedPort = Integer.parseInt(cmd.getOptionValue("sport"));
                } catch (NumberFormatException e) {
                    System.out.println("Please give a valid port number." + cmd.getOptionValue("sport"));
                    System.exit(1);
                }
            }
            if (cmd.hasOption("secret")) {
                secret = cmd.getOptionValue("secret");
            } else {
                secret = randomAlphabetic(26);
            }

            // Print logfile info when starting
            logr_info.info("Using secret: " + secret);
            logr_info.info("Using advertised hostname: " + hostname);
            logr_info.info("Bound to normal port " + port);
            logr_info.info("Bound to secured port " + securedPort);
            logr_info.info("Started");
            BufferedReader br = new BufferedReader(new FileReader("./serverLog.log"));
            String sCurrentLine;
            while ((sCurrentLine = br.readLine()) != null) {
                System.out.println(sCurrentLine);
            }

            if (cmd.hasOption("debug")) {
                setupDebug();
                logr_debug.info("Setting debug on");
            }

            // Add current host and secured port to serverList.
            JSONObject securedLocalHost = new JSONObject();
            securedLocalHost.put("hostname", getRealIp());
            securedLocalHost.put("port", securedPort);
            securedServerList.add(securedLocalHost);

            // Add current host and unsecured port to serverList.
            JSONObject unsecuredLocalHost = new JSONObject();
            unsecuredLocalHost.put("hostname", getRealIp());
            unsecuredLocalHost.put("port", port);
            unsecuredServerList.add(unsecuredLocalHost);

            // Create thread for periodical exchange of secured servers.
            Thread tSecuredExchange = new Thread(() -> timingExchange(cmd, securedServerList, true));
            tSecuredExchange.start();

            // Create thread for periodical exchange of unsecured servers.
            Thread tUnsecuredExchange = new Thread(() -> timingExchange(cmd, unsecuredServerList, false));
            tUnsecuredExchange.start();

            // Create thread for secured socket
            Thread tSecured = new Thread(() -> securedSocket(cmd));
            tSecured.start();

            // Create thread for unsecured socket
            Thread tUnsecured = new Thread(() -> unsecuredSocket(cmd));
            tUnsecured.start();

        } catch (IOException e){
            e.printStackTrace();
        }
    }

    /**
     * This function is used for listening on secured port and connecting with secured clients.
     * @param cmd this is the parsed command from command line input when running server.
     */
    private static void securedSocket(CommandLine cmd) {
        try {
            System.setProperty("javax.net.ssl.keyStore","serverKeyStore/server-keystore.jks");
            System.setProperty("javax.net.ssl.trustStore", "serverKeyStore/server-keystore.jks");
            System.setProperty("javax.net.ssl.keyStorePassword","Dr.Stranger");
//            System.setProperty("javax.net.debug","all");

            SSLServerSocketFactory sslFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket sslServerSocket = (SSLServerSocket) sslFactory.createServerSocket(securedPort);

            // Create thread for each secured client
            while(true) {
                SSLSocket sslClient = (SSLSocket)sslServerSocket.accept();
                Thread tSecured = new Thread(() -> serveClient(sslClient, cmd, securedServerList, true));
                tSecured.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This function is used for listening on common port and connecting with common clients.
     * @param cmd this is the parsed command from command line input when running server.
     */
    private static void unsecuredSocket(CommandLine cmd) {
        try {
            ServerSocketFactory factory = ServerSocketFactory.getDefault();
            ServerSocket server = factory.createServerSocket(port);

            // Create thread for each unsecured client
            while(true) {
                Socket client = server.accept();
                Thread t = new Thread(() -> serveClient(client, cmd, unsecuredServerList, false));
                t.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This function is used for receiving data from client, calling functions according to client's command,
     * and sending response back to client.
     * @param client this is the socket connection with client.
     * @param args this is the parsed command from command line input when running server.
     */
    private static void serveClient(Socket client, CommandLine args, JSONArray serverList, Boolean secure) {
        String receiveData;
        JSONObject cmd;
        JSONObject msg = null;
        JSONArray fileResponse = null;
        JSONArray sendMsg = new JSONArray();
        Date date = new Date();

        try(Socket clientSocket = client) {
            // Check connection interval limit, if less than lower requirement, close the connection with processing.
            String getAddress = clientSocket.getInetAddress().getHostAddress();
            logr_debug.fine("The connection with " + getAddress + ":" + clientSocket.getPort() + " has been established.");
            Long time = date.getTime();
            if (clientList.containsKey(getAddress)) {
                if (time - clientList.get(getAddress) < connectionSecond * 1000) {
                    logr_debug.fine("The request from host " + getAddress + " is too frequent.");
                    clientSocket.close();
                    logr_debug.fine("The connection with " + getAddress + ":" + clientSocket.getPort() + " has been closed by server.");
                }
            }
            clientList.put(getAddress, time);
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
            FileInputStream file = null;
            // Call different functions based on client's command.
            do {
                receiveData = in.readUTF();
                logr_debug.fine("RECEIVED: " + receiveData);
                cmd = JSONObject.fromObject(receiveData);
                if (!cmd.containsKey("command")) {
                    msg.put("response", "error");
                    msg.put("errorMessage", "missing or incorrect type for command");
                    sendMsg.add(msg);
                } else {
                    switch (cmd.get("command").toString()) {
                        case "PUBLISH":
                            sendMsg.add(PublishNShare.publish(cmd, resourceList, keys, getRealIp(),
                                    clientSocket.getLocalPort()));
                            break;
                        case "REMOVE":
                            sendMsg.add(RemoveNFetch.remove(cmd, resourceList, keys));
                            break;
                        case "SHARE":
                            sendMsg.add(PublishNShare.share(cmd, resourceList, keys, secret,
                                    getRealIp(), clientSocket.getLocalPort()));
                            break;
                        case "FETCH":
                            fileResponse = RemoveNFetch.fetch(cmd, resourceList);
                            sendMsg.addAll(fileResponse);
                            if (fileResponse.getJSONObject(0).get("response").equals("success")) {
                                String uri = cmd.getJSONObject("resourceTemplate").get("uri").toString();
                                file = new FileInputStream(uri);
                            }
                            break;
                        case "QUERY":
                            sendMsg.addAll(QueryNExchange.query(cmd, resourceList, serverList, secure));
                            break;
                        case "EXCHANGE":
                            sendMsg.addAll(QueryNExchange.exchange(cmd, serverList));
                            break;
                        case "SUBSCRIBE":
                            JSONObject m = Subscribe.init(cmd);
                            sendMsg.add(m);
                            if(m.get("response").equals("success")) {
                                Subscribe.subscribe(cmd, clientSocket, resourceList, secure, logr_debug);
                            }
                            break;
                        default:
                            msg.put("response", "error");
                            msg.put("errorMessage", "invalid command");
                            sendMsg.add(msg);
                            break;
                    }
                }
                if (cmd.containsKey("command") && !cmd.get("command").toString().equals("SUBSCRIBE")) {
                    logr_debug.fine("SENT: " + sendMsg.toString());
                    for (int i = 0; i < sendMsg.size(); i++) {
                        out.writeUTF(sendMsg.getJSONObject(i).toString());
                    }
                    Thread.sleep(1000);
                    out.flush();
                    // Sending fetched file to client.
                    if (cmd.get("command").toString().equals("FETCH") && fileResponse.getJSONObject(0).get("response").equals("success")) {
                        byte[] buffer = new byte[4000];
                        while (file.read(buffer) > 0) {
                            out.write(buffer);
                        }
                        out.flush();
                        file.close();
                    }
                }
            } while(in.available() > 0);
            out.close();
            clientSocket.close();
            logr_debug.fine("The connection with " + getAddress + ":" + clientSocket.getPort() + " has been closed.");
            if (args.hasOption("debug")) {
                BufferedReader brDebug = new BufferedReader(new FileReader("./debug_" + getRealIp() + "_" + port +".log"));
                String dCurrentLine;
                while ((dCurrentLine = brDebug.readLine()) != null) {
                    System.out.println(dCurrentLine);
                }
                setupDebug();
            }
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

    private static void setupLogger() {
        LogManager.getLogManager().reset();
        logr_info.setLevel(Level.ALL);
        try {
            FileHandler fh = new FileHandler("serverLog.log");
            fh.setLevel(Level.FINE);
            logr_info.addHandler(fh);
            MyFormatter formatter = new MyFormatter();
            fh.setFormatter(formatter);
        } catch (java.io.IOException e) {
            logr_info.finer("File logger is not working.");
        }
    }

    private static void setupDebug() {
        LogManager.getLogManager().reset();
        logr_debug.setLevel(Level.ALL);
        try {
            FileHandler fh = new FileHandler("debug_" + getRealIp() + "_" + port +".log");
            fh.setLevel(Level.FINE);
            logr_debug.addHandler(fh);
            MyFormatter formatter = new MyFormatter();
            fh.setFormatter(formatter);
        } catch (java.io.IOException e) {
            logr_debug.finer("Debug logger is not working.");
        }
    }

    /**
     * This function is used for periodically exchanging serverList with a random selected server on serverList.
     * @param args this is the parsed command from command line input when running server.
     */
    public static void timingExchange (CommandLine args, JSONArray serverList, Boolean secure) {
        String receiveData;
        try {
            while (true) {
                if (serverList.size() > 1) {
                    int select = 1 + (int) (Math.random() * (serverList.size() - 1));
                    String host = serverList.getJSONObject(select).get("hostname").toString();
                    int exchangePort = Integer.parseInt(serverList.getJSONObject(select).get("port").toString());
                    JSONObject cmd = new JSONObject();
                    cmd.put("command", "EXCHANGE");
                    cmd.put("serverList", serverList);
                    logr_debug.fine("Auto-exchange is working in every " + exchangeSecond + " seconds.");
                    logr_debug.fine("SENT: " + cmd.toString());
                    if (secure) {
                        receiveData = QueryNExchange.securedServerSend(host, exchangePort, cmd.toString());
                    } else {
                        receiveData = QueryNExchange.serverSend(host, exchangePort, cmd.toString());
                    }
                    logr_debug.fine("RECEIVED: " + receiveData);
                    logr_debug.fine("Auto-exchange is finished.");
                    if (receiveData.equals("connection failed")) {
                        serverList.remove(select);
                    }
                    if (args.hasOption("debug")) {
                        BufferedReader brDebug = new BufferedReader(new FileReader("./debug_" + getRealIp() + "_" + port +".log"));
                        String dCurrentLine;
                        while ((dCurrentLine = brDebug.readLine()) != null) {
                            System.out.println(dCurrentLine);
                        }
                        setupDebug();
                    }
                }
                Thread.sleep(exchangeSecond * 1000);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This function is used for getting the IP address of current server.
     * @return will return the String of IP address.
     */
    public static String getRealIp() throws SocketException {
        String localIp = null;
        String netIp = null;

        Enumeration<NetworkInterface> netInterfaces = NetworkInterface.getNetworkInterfaces();
        InetAddress ip;
        boolean find = false;
        while (netInterfaces.hasMoreElements() && !find) {
            NetworkInterface ni = netInterfaces.nextElement();
            Enumeration<InetAddress> address = ni.getInetAddresses();
            while (address.hasMoreElements()) {
                ip = address.nextElement();
                if (!ip.isSiteLocalAddress()
                        && !ip.isLoopbackAddress()
                        && !ip.getHostAddress().contains(":")) {
                    netIp = ip.getHostAddress();
                    find = true;
                    break;
                } else if (ip.isSiteLocalAddress()
                        && !ip.isLoopbackAddress()
                        && !ip.getHostAddress().contains(":")) {
                    localIp = ip.getHostAddress();
                }
            }
        }

        if (netIp != null && !netIp.equals("")) {
            return netIp;
        } else {
            return localIp;
        }
    }
}