/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package transportservice;

public class ExtensionSettings {

    private String extensionname;
    private String hostaddress;
    private String hostport;
    private String description;
    private String version;
    private String opensearchversion;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getOpensearchversion() {
        return opensearchversion;
    }

    public void setOpensearchversion(String opensearchversion) {
        this.opensearchversion = opensearchversion;
    }
}
