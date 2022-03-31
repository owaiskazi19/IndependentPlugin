package transportservice;

public class ExtensionSettings {

    public ExtensionSettings(String nodename, String hostaddress, String hostport) {
        this.nodename = nodename;
        this.hostaddress = hostaddress;
        this.hostport = hostport;
    }

    public ExtensionSettings() {}

    private String nodename;
    private String hostaddress;
    private String hostport;

    public String getNodeName() {
        return nodename;
    }

    public void setNodeName(String nodename) {
        this.nodename = nodename;
    }

    public String getHostAddress() {
        return hostaddress;
    }

    public void getHostAddress(String hostaddress) {
        this.hostaddress = hostaddress;
    }

    public String getHostPort(){
        return hostport;
    }

    public void setHostPort(String hostport) {
        this.hostport = hostport;
    }

    @Override
    public String toString() {
        return "\nnodename: " + nodename + "\nhostaddress: "
                + hostaddress + "\nhostPort: " + hostport
                + "\n";
    }

}
