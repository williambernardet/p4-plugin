package jenkins.plugins.p4;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import org.apache.commons.lang.StringUtils;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.client.IClientSummary;
import com.perforce.p4java.core.IChangelist;
import com.perforce.p4java.core.IChangelistSummary;
import com.perforce.p4java.core.file.FileSpecBuilder;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.impl.generic.client.ClientOptions;
import com.perforce.p4java.impl.generic.core.ChangelistSummary;
import com.perforce.p4java.impl.mapbased.client.Client;
import com.perforce.p4java.option.client.SyncOptions;
import com.perforce.p4java.server.IServer;
import com.perforce.p4java.server.ServerFactory;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;

/**
 * Perforce SCM support for Jenkins. Perforce operations are done through
 * P4Java API, http://www.perforce.com/perforce/doc.current/manuals/p4java/index.html
 * 
 * @author mitapani
 *
 */
public class P4SCM extends SCM {

    private String p4Port;
    private String p4User;
    private String p4Passwd;
    private String p4Client;
    private String p4Stream;
    
    private static final Logger LOGGER = Logger.getLogger(P4SCM.class.getName());
    
    /**
     * Log formatting helper
     */
    private static final int RIGHTPAD_SIZE = 30;
    
    /**
     * The maximum number of changelists to look back in history.
     */
    private static final int MAX_CHANGELISTS = 100;
    
    @DataBoundConstructor
    public P4SCM(
            String p4Port,
            String p4User,
            String p4Passwd,
            String p4Client,
            String p4Stream) {
        
        this.p4Port = p4Port;
        this.p4User = p4User;
        this.p4Passwd = p4Passwd;
        this.p4Client = p4Client;
        this.p4Stream = p4Stream;
    }
    
    
    /**
     * @return the p4Port
     */
    public String getP4Port() {
        return p4Port;
    }


    /**
     * @param p4Port the p4Port to set
     */
    public void setP4Port(String p4Port) {
        this.p4Port = p4Port;
    }


    /**
     * @return the p4User
     */
    public String getP4User() {
        return p4User;
    }


    /**
     * @param p4User the p4User to set
     */
    public void setP4User(String p4User) {
        this.p4User = p4User;
    }


    /**
     * @return the p4Passwd
     */
    public String getP4Passwd() {
        return p4Passwd;
    }


    /**
     * @param p4Passwd the p4Passwd to set
     */
    public void setP4Passwd(String p4Passwd) {
        this.p4Passwd = p4Passwd;
    }


    /**
     * @return the p4Client
     */
    public String getP4Client() {
        return p4Client;
    }


    /**
     * @param p4Client the p4Client to set
     */
    public void setP4Client(String p4Client) {
        this.p4Client = p4Client;
    }


    /**
     * @return the p4Stream
     */
    public String getP4Stream() {
        return p4Stream;
    }


    /**
     * @param p4Stream the p4Stream to set
     */
    public void setP4Stream(String p4Stream) {
        this.p4Stream = p4Stream;
    }


    @Override
    public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> build,
            Launcher launcher, TaskListener listener) throws IOException,
            InterruptedException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected PollingResult compareRemoteRevisionWith(
            AbstractProject<?, ?> project, Launcher launcher,
            FilePath workspace, TaskListener listener, SCMRevisionState baseline)
            throws IOException, InterruptedException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean checkout(AbstractBuild<?, ?> build, Launcher launcher,
            FilePath workspace, BuildListener listener, File changelogFile)
            throws IOException, InterruptedException {
        
        PrintStream log = listener.getLogger();
        String serverUriString = "p4java://" + p4Port;

        if (!isConfigurationValid(log)) {
            return false;
        }

        try {
            LOGGER.finest("Connecting to server: '" + serverUriString + "'.");
            IServer server = ServerFactory.getServer(serverUriString, null);
            server.connect();
            
            LOGGER.finest("Login as user '" + p4User + "'.");
            server.setUserName(p4User);
            server.login(p4Passwd);
            
            IClient currentClient = getClient(server, workspace);
            server.setCurrentClient(currentClient);

            IChangelistSummary lastChange = getLastChange((Run)build.getPreviousBuild());
            IChangelistSummary newestChange = getNewestChange(server);
            
            String lastBuiltChange = lastChange.getId()==IChangelist.UNKNOWN ? "no previous builds" : Integer.toString(lastChange.getId());
            log.println(StringUtils.rightPad("P4PORT:", RIGHTPAD_SIZE, ".") + p4Port);
            log.println(StringUtils.rightPad("P4USER:", RIGHTPAD_SIZE, ".")+ p4User);
            log.println(StringUtils.rightPad("P4CLIENT:", RIGHTPAD_SIZE, ".") + currentClient.getName());
            log.println(StringUtils.rightPad("Last built changelist:", RIGHTPAD_SIZE, ".") + lastBuiltChange);
            log.println(StringUtils.rightPad("Syncing to changelist:", RIGHTPAD_SIZE, ".") + newestChange.getId());
            
            createChangelist(server, lastChange, newestChange, log);
            
            List<IFileSpec> syncList = currentClient.sync(
                    FileSpecBuilder.makeFileSpecList("//..."),
                    new SyncOptions());

            build.addAction(new P4SCMRevisionState(newestChange));
            
            if (server != null) {
                server.disconnect();
            }
        } catch (ConnectionException cexc) {
            log.println(cexc.getLocalizedMessage());
            LOGGER.finest("*** ERROR: " + cexc.getMessage());
            cexc.printStackTrace();
            return false;
        } catch (RequestException rexc) {
            log.println(rexc.getDisplayString());
            LOGGER.finest("*** ERROR: " + rexc.getMessage());
            rexc.printStackTrace();
            return false;
        } catch (P4JavaException exc) {
            log.println(exc.getLocalizedMessage());
            LOGGER.finest("*** ERROR: " + exc.getMessage());
            exc.printStackTrace();
            return false;
        } catch (URISyntaxException e) {
            log.println(e.getLocalizedMessage());
            LOGGER.finest("*** ERROR: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Check the validity of configuration options.
     * 
     * @param log Log handle for error reports
     * @return true if everything is ok, false otherwise
     */
    private boolean isConfigurationValid(PrintStream log) {
        
        if (p4Port==null || p4Port.trim().isEmpty()) {
            log.println("*** ERROR: P4PORT missing!");
            return false;
        }
        if (p4User==null || p4User.trim().isEmpty()) {
            log.println("*** ERROR: P4USER missing!");
            return false;
        }
        if (p4Client==null || p4Client.trim().isEmpty()) {
            log.println("*** ERROR: P4CLIENT missing!");
            return false;
        }
        if (p4Stream==null || p4Stream.trim().isEmpty()) {
            log.println("*** ERROR: Stream missing!");
            return false;
        }
        
        return true;
    }
    
    /**
     * Get the existing p4 client or create a new one.
     * 
     * @param server Perforce server
     * @param workspace Jenkins workspace
     * @return Current p4 client for the build
     * @throws IOException
     * @throws InterruptedException
     * @throws AccessException
     * @throws RequestException
     * @throws ConnectionException
     */
    private IClient getClient(IServer server, FilePath workspace) 
            throws IOException, InterruptedException, AccessException, 
            RequestException, ConnectionException {
        
        IClient client;
        // Use unique client for each node and eliminate spaces.
        String p4ClientEffective = p4Client.replaceAll(" ", "_");
        if (Computer.currentComputer() != null) {
            if (Computer.currentComputer().getName() != null && !Computer.currentComputer().getName().trim().isEmpty()) {
                p4ClientEffective += "_" + Computer.currentComputer().getName();
            }
        }
        
        List<IClientSummary> clientList = server.getClients(p4User, p4ClientEffective, 0);
        
        if (clientList != null) {
            for (IClientSummary clientSummary : clientList) {
                if (clientSummary.getName().equals(p4ClientEffective)) {
                    LOGGER.finest("Found existing p4 client '" + p4ClientEffective + "'.");
                    // Save possible changes to the client.
                    client = server.getClient(clientSummary);
                    safeClientIfDirty(client, workspace);
                    return client;
                }
            }
        }
        
        LOGGER.finest("Creating new p4 client '" + p4ClientEffective + "'.");
        client = new Client();
        client.setName(p4ClientEffective);
        client.setAccessed(new Date());
        client.setUpdated(new Date());
        client.setDescription("Created by Jenkins P4 plugin");
        client.setHostName(getEffectiveHostName());
        client.setOwnerName(p4User);
        client.setRoot(workspace.getRemote());
        client.setLineEnd(IClientSummary.ClientLineEnd.LOCAL);
        client.setOptions(new ClientOptions(true,true,false,false,false,true));
        client.setServer(server);
        client.setStream(p4Stream);

        server.createClient(client);
        
        return client;
    }
    
    /**
     * Get the effective host name.
     * 
     * @return Host name without the domain name or UNKNOWNHOST if Jenkins is
     *          unable to retrieve the host name.
     * @throws IOException
     * @throws InterruptedException
     */
    private String getEffectiveHostName()
            throws IOException, InterruptedException {

        String host = Computer.currentComputer().getHostName();
        
        if (host == null) {
            LOGGER.finest("Could not get host name for slave " + Computer.currentComputer().getDisplayName());
            host = "UNKNOWNHOST";
        }

        if (host.contains(".")) {
            host = String.valueOf(host.subSequence(0, host.indexOf('.')));
        }
        LOGGER.finest("Using host '" + host + "'.");
        return host;
    }
    
    /**
     * Save the client specification if there are modifications to it.
     * 
     * @param client The client to be modified.
     */
    private void safeClientIfDirty(IClient client, FilePath workspace) {
        if (!p4Stream.equals(client.getStream())) {
            client.setStream(p4Stream);
        }
        if (!p4User.equals(client.getOwnerName())) {
            client.setOwnerName(p4User);
        }
        if (!workspace.getRemote().equals(client.getRoot())) {
            client.setRoot(workspace.getRemote());
        }
    }
    
    /**
     * Get the last built changelist ID.
     * 
     * @param build The build from where to start searching. Go back
     *      one by one until changelist is found.
     * @return Last built changelist as {@link IChangelistSummary}. If none found,
     *      return an object with id {@link IChangelist.UNKNOWN}.
     */
    private static IChangelistSummary getLastChange(Run build) {
        
        LOGGER.finest("Searching the last built changelist ID...");
        P4SCMRevisionState revision = getMostRecentRevision(build);
        if (revision == null) {
            LOGGER.finest("Could not find previously built changelist.");
            return new ChangelistSummary();
        }

        LOGGER.finest("Found changelist: '" + revision.getRevision().getId() + "'.");
        return revision.getRevision();
    }
    
    /**
     * Get the most recent {@link P4SCMRevisionState}.
     * 
     * @param build The build from where to start searching. Go back
     *      one by one until {@link P4SCMRevisionState} is found.
     * @return
     */
    private static P4SCMRevisionState getMostRecentRevision(Run build) {
        if (build == null)
            return null;

        P4SCMRevisionState revision = build.getAction(P4SCMRevisionState.class);
        if (revision != null)
            return revision;

        // If build had no actions, keep going back until we find one that does.
        return getMostRecentRevision(build.getPreviousBuild());
    }
    
    /**
     * Get the newest changelist ID.
     * 
     * @param server Current perforce server object.
     * @return Newest changelist ID as {@link IChangelistSummary}.
     * @throws AccessException 
     * @throws RequestException 
     * @throws ConnectionException 
     */
    private static IChangelistSummary getNewestChange(IServer server) 
            throws ConnectionException, RequestException, AccessException {
        
        List<IChangelistSummary> allChanges = server.getChangelists(MAX_CHANGELISTS, 
                FileSpecBuilder.makeFileSpecList("//..."), null, null, false, true, false, false);
        IChangelistSummary newestChange = allChanges.get(0);
        
        return newestChange;
    }
    
    
    private static void createChangelist(IServer server, IChangelistSummary lastChange, 
            IChangelistSummary newestChange, PrintStream log) 
                    throws ConnectionException, RequestException, AccessException {
        
        List<IChangelistSummary> allChanges = server.getChangelists(MAX_CHANGELISTS, 
                FileSpecBuilder.makeFileSpecList("//..."), null, null, false, true, false, false);
        
        List<IChangelistSummary> filteredChanges;
        
        log.println("Calculating changelog...");
        
        if (allChanges != null) {
            for (IChangelistSummary changelistSummary : allChanges) {
                log.println(changelistSummary.getId());
            }
        }
    }
    
    @Extension
    public static final class P4SCMDescriptor extends SCMDescriptor<P4SCM> {
        public P4SCMDescriptor() {
            super(P4SCM.class, null);
            load();
        }

        public String getDisplayName() {
            return "P4";
        }

        @Override
        public SCM newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            P4SCM newInstance = (P4SCM)super.newInstance(req, formData);
            newInstance.setP4Port(formData.getString("p4Port"));
            newInstance.setP4User(formData.getString("p4User"));
            newInstance.setP4Passwd(formData.getString("p4Passwd"));
            newInstance.setP4Client(formData.getString("p4Client"));
            newInstance.setP4Stream(formData.getString("p4Stream"));
            return newInstance;
        }
        
        public String getAppName() {
            return Hudson.getInstance().getDisplayName();
        }
    }
}
