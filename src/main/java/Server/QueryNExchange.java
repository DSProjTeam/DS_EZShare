package Server;
//package main.java.Server;

/**
 * This class is used for querying and exchanging functions on EZShare System.
 * @author: Jiayu Wang
 * @date: April 1, 2017
 */

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;

public class QueryNExchange {
    /**
     * This function is mainly for parsing the "relay" argument and overall control.
     * @param command client command
     * @param resourceList central resource list
     * @param serverList central server list
     * @return will return a JSONArray fullQueryList
     */
    public static JSONArray query (JSONObject command, HashMap<Integer, Resource> resourceList, JSONArray serverList) {
        boolean relay = Boolean.parseBoolean(command.get("relay").toString());
        JSONObject response = new JSONObject();
        JSONObject size = new JSONObject();
        JSONArray fullQueryList = new JSONArray();

        if(!command.containsKey("resourceTemplate")) {
            response.put("response","error");
            response.put("errorMessage","missing resourceTemplate");
            fullQueryList.add(response);
            return fullQueryList;
        } else {
            response.put("response", "success");
            fullQueryList.add(response);
            if (!relay) {
                fullQueryList.addAll(selfQuery(command, resourceList));
            } else {
                command.put("relay", false);
                fullQueryList.addAll(selfQuery(command, resourceList));
                for(int i = 1; i < serverList.size(); i++) {
                    fullQueryList.addAll(otherQuery(serverList.getJSONObject(i), command));
                }
            }
            size.put("resultSize", fullQueryList.size() - 1);
            fullQueryList.add(size);
            return fullQueryList;
        }
    }

    /**
     * This function is used for query the resource on current server.
     * @param command
     * @param resourceList
     * @return JSONArray
     */
    public static JSONArray selfQuery(JSONObject command, HashMap<Integer, Resource> resourceList) {
        JSONArray queryList = new JSONArray();
        JSONObject cmd = JSONObject.fromObject(command.get("resourceTemplate"));
        JSONArray cmdTagsJson = cmd.getJSONArray("tags");
        String[] cmdTags = cmdTagsJson.toString().substring(1, cmdTagsJson.toString().length() - 1).split(",");
        String cmdName = cmd.get("name").toString();
        String cmdDescription = cmd.get("description").toString();

        for(Resource resource : resourceList.values()) {
            Resource src = new Resource(resource);
            Boolean channel = true, owner = true, tags = true, uri = true;
            Boolean name = false, description = false, nameDescription = false;

            if (!cmd.get("channel").equals("") && !cmd.get("channel").equals(src.getChannel())) {
                channel = false;
            } else if (cmd.get("channel").equals("") && !src.getChannel().equals("")) {
                channel = false;
            }
            if (!cmd.get("owner").equals("") && !cmd.get("owner").equals(src.getOwner())) {
                owner = false;
            }
            if (cmdTags.length != 0 && !cmdTags[0].equals("")) {
                for(int j = 0; j < cmdTags.length; j++) {
                    if (!src.getTags().contains(cmdTags[j])) {
                        tags = false;
                    }
                }
            }
            if (!cmd.get("uri").equals("") && !cmd.get("uri").equals(src.getUri())) {
                uri = false;
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
                src.setOwner("*");
                queryList.add(src.toJSON());
            }
        }
        return queryList;
    }

    /**
     * This function is for one server to query the resource on another server, which is accomplished by forwarding the
     * query command to another server.
     * @param serverPort this is the host and port pair for the other server that we are going to query
     * @param command
     * @return JSONArray
     */
    public static JSONArray otherQuery(JSONObject serverPort, JSONObject command) {
        String server = serverPort.get("hostname").toString();
        int port = Integer.parseInt(serverPort.get("port").toString());
        String sendData = command.toString();
        JSONArray queryList;

        String receiveData = serverSend(server, port, sendData);
        queryList = JSONArray.fromObject(receiveData);

        if (queryList.size() >= 3){
            queryList.remove(queryList.size() - 1);
            queryList.remove(0);
            return queryList;
        } else {
            return null;
        }
    }

    /**
     * This function is used for exchange command.
     * @param command
     * @param serverList
     * @return JSONArray
     */
    public static JSONArray exchange (JSONObject command, JSONArray serverList) throws SocketException {
        JSONArray newList;
        JSONArray msgArray = new JSONArray();
        JSONObject msg = new JSONObject();

        if (command.containsKey("serverList")) {
            if (command.getJSONArray("serverList").size() != 0) {
                newList = command.getJSONArray("serverList");
                for (int i = 0; i < newList.size(); i++) {
                    if (!serverList.contains(newList.getJSONObject(i))) {
                        if (serverList.getJSONObject(0).get("port").equals(newList.getJSONObject(i).get("port"))) {
                            if (newList.getJSONObject(i).get("hostname").equals("localhost") ||
                                    newList.getJSONObject(i).get("hostname").equals("127.0.0.1") ||
                                    newList.getJSONObject(i).get("hostname").equals(Server.getRealIp())) {
                                continue;
                            }
                        }
                        serverList.add(newList.getJSONObject(i));
                    }
                }
                msg.put("response", "success");
            } else {
                msg.put("response", "error");
                msg.put("errorMessage", "missing or invalid serverList");
            }
        } else {
            msg.put("response", "error");
            msg.put("errorMessage", "missing serverList");
        }
        msgArray.add(msg);
        return msgArray;
    }

    /**
     * This function is used for sending and receiving data.
     * @param server this represents for the target host you would like to connect with.
     * @param port
     * @param data the data to be sent to other server.
     * @return String, the data received from other server.
     */
    public static String serverSend(String server, int port, String data) {
        String receiveData = "";
        try {
            Socket connection = new Socket(server, port);
            DataInputStream in = new DataInputStream(connection.getInputStream());
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());

            out.writeUTF(data);
            out.flush();

            do {
                receiveData = in.readUTF();
                Thread.sleep(1000);
            } while (in.available() > 0);

            connection.close();

        } catch (IOException e){
            e.printStackTrace();
            receiveData = "connection failed";
        } finally {
            return receiveData;
        }
    }
}