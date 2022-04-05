package transportservice;

public class ExtensionSettings {

    private String extensionname;
    private String hostaddress;
    private String hostport;
    // Change the location to extension.yml file of the extension
    static final String EXTENSION_DESCRIPTOR = "src/test/resources/extension.yml";

    public String getExtensionname() {
        return extensionname;
    }

    public void setExtensionname(String extensionname) {
        this.extensionname = extensionname;
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
        return "\nnodename: " + extensionname + "\nhostaddress: " + hostaddress + "\nhostPort: " + hostport + "\n";
    }

}
