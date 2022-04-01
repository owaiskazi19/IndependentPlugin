package transportservice;

public class ExtensionSettings {

    private String nodename;
    private String hostaddress;
    private String hostport;

    public String getNodename() {
        return nodename;
    }

    public void setNodename(String nodename) {
        this.nodename = nodename;
    }

    public String getHostaddress() {
        return hostaddress;
    }

    public void getHostaddress(String hostaddress) {
        this.hostaddress = hostaddress;
    }

    public String getHostport() {
        return hostport;
    }

    public void setHostport(String hostport) {
        this.hostport = hostport;
    }

    @Override
    public String toString() {
        return "\nnodename: " + nodename + "\nhostaddress: " + hostaddress + "\nhostPort: " + hostport + "\n";
    }

}
