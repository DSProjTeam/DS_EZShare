/**
 * Created by think on 2017/3/26.
 */
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;

public class MultiThread extends Thread {
    private static ArrayList<Resource> resourceList = new ArrayList<>();
    private static ArrayList<HashMap> serverList = new ArrayList<>();
    private Socket socket = null;
    private String receiveData;
    private String sendData;
    private JSONObject cmd;
    private JSONObject msg = null;

    public MultiThread(Socket socket) {
        this.socket = socket;
    }

    public void run() {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            while(in.available() > 0) {
                receiveData = in.readUTF();
                cmd = JSONObject.fromObject(receiveData);
                switch(cmd.get("command").toString()) {
                    case "PUBLISH": publish(cmd); break;
                    case "REMOVE": remove(cmd); break;
                    case "SHARE": share(cmd); break;
                    case "FETCH": fetch(cmd); break;
                    case "QUERY": query(cmd); break;
                    case "EXCHANGE": exchange(cmd); break;
                    default:
                        msg.put("response", "error");
                        msg.put("errorMessage", "invalid command");
                        out.writeUTF(msg.toString());
                        out.flush();
                        break;
                }
                in.close();
                out.close();
                socket.close();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //This function is mainly for parsing the "relay" argument and overall control.
    public JSONArray query (JSONObject command) {
        Boolean relay = (Boolean)command.get("relay");
        JSONArray fullQueryList;
        if (!relay) {
            return selfQuery(command);
        } else {
            command.put("relay", false);
            fullQueryList = selfQuery(command);
            for(int i = 0; i < serverList.size(); i++) {
                fullQueryList.addAll(otherQuery(serverList.get(i), command));
            }
            return fullQueryList;
        }
    }

    //This function is used for query the resource on this server.
    public JSONArray selfQuery(JSONObject command) {
        JSONObject cmd = JSONObject.fromObject(command.get("resourceTemplate"));
        JSONArray queryList = new JSONArray();

        for(int i = 0; i < resourceList.size(); i++) {
            Boolean channel = false, owner = false, tags = true, uri = false;
            Boolean name = false, description = false, nameDescription = false;
            Resource src = resourceList.get(i);
            String[] cmdTags = (String[])cmd.get("tags");
            String cmdName = cmd.get("name").toString();
            String cmdDescription = cmd.get("description").toString();

            if (cmd.get("channel").equals(src.getChannel())) {
                channel = true;
            }
            if (cmd.get("owner").equals(src.getOwner())) {
                owner = true;
            }
            if (cmdTags.length != 0) {
                for(int j = 0; j < cmdTags.length; j++) {
                    if (!src.getTags().contains(cmdTags[j])) {
                        tags = false;
                    }
                }
            }
            if (cmd.get("uri").equals(src.getUri())) {
                uri = true;
            }
            if ((!cmdName.equals("")) && src.getName().contains(cmdName)) {
                name = true;
            }
            if ((!cmdDescription.equals("")) && src.getDescription().contains(cmdDescription)) {
                description = true;
            }
            if (cmdName.equals("") && cmdDescription.equals("")) {
                nameDescription = true;
            }

            if (channel && owner && tags && uri && (name || description || nameDescription)) {
                queryList.add(src.toJSON());
            }
        }

        return queryList;
    }

    // This function is for one server to query the resource on another server.
    public JSONArray otherQuery(HashMap serverPort, JSONObject command) {
        String server = serverPort.get("hostname").toString();
        int port = (int)serverPort.get("port");
        String sendData = command.toString(); //我这里相当于假定Client端负责解析，Server端收到的是toString过的JSON
        JSONArray queryList;

        String receiveData = serverSend(server, port, sendData);
        queryList = JSONArray.fromObject(receiveData);

        return queryList;
    }

    // This function is for one server to send data to another server.
    public static String serverSend(String server, int port, String data) {
        String receiveData = "";
        try {
            Socket connection = new Socket(server, port);
            DataInputStream in = new DataInputStream(connection.getInputStream());
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());

            out.writeUTF(data);
            out.flush();
            receiveData = in.readUTF();

            connection.close();

        } catch (IOException e){
            e.printStackTrace();
        } finally {
            return receiveData;
        }
    }
}