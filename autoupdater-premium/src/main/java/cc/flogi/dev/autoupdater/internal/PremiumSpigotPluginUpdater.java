package cc.flogi.dev.autoupdater.internal;

import be.maximvdw.spigotsite.SpigotSiteCore;
import be.maximvdw.spigotsite.api.SpigotSiteAPI;
import be.maximvdw.spigotsite.api.exceptions.ConnectionFailedException;
import be.maximvdw.spigotsite.api.resource.Resource;
import be.maximvdw.spigotsite.api.user.User;
import be.maximvdw.spigotsite.api.user.exceptions.InvalidCredentialsException;
import be.maximvdw.spigotsite.api.user.exceptions.TwoFactorAuthenticationException;
import be.maximvdw.spigotsite.user.SpigotUser;
import cc.flogi.dev.autoupdater.api.SpigotPluginUpdater;
import cc.flogi.dev.autoupdater.api.UpdateLocale;
import cc.flogi.dev.autoupdater.api.UpdaterRunnable;
import cc.flogi.dev.autoupdater.api.exceptions.NoUpdateFoundException;
import cc.flogi.dev.autoupdater.api.exceptions.ResourceNotPurchasedException;
import cc.flogi.dev.autoupdater.api.exceptions.UserExitException;
import com.gargoylesoftware.htmlunit.*;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.util.Cookie;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.ChatColor;
import net.wesjd.anvilgui.AnvilGUI;
import org.apache.commons.logging.LogFactory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Caden Kriese (flogic)
 *
 * Performs updates for premium Spigot plugins.
 *
 * Created on 6/6/17
 */
public class PremiumSpigotPluginUpdater implements SpigotPluginUpdater {
    private static final String SPIGET_BASE_URL = "https://api.spiget.org/v2/resources/";
    private static final JsonParser JSON_PARSER = new JsonParser();

    private static User currentUser;
    private static SpigotSiteAPI siteAPI;
    private static WebClient webClient;
    private Player initiator;
    private Plugin plugin;
    private String pluginFolderPath;
    private String currentVersion;
    private String downloadUrl;
    private String pluginName;
    private UpdateLocale locale;
    private Resource resource;
    private UpdaterRunnable endTask;
    private boolean replace;
    private int resourceId;
    private int loginAttempts;
    private long startingTime;

    protected PremiumSpigotPluginUpdater(Player initiator, Plugin plugin, int resourceId, UpdateLocale locale, boolean replace) {
        this(initiator, plugin, resourceId, locale, replace, (successful, ex, updatedPlugin, pluginName) -> {});
    }

    protected PremiumSpigotPluginUpdater(Player initiator, Plugin plugin, int resourceId, UpdateLocale locale, boolean replace, UpdaterRunnable endTask) {
        pluginFolderPath = plugin.getDataFolder().getParent();
        currentVersion = plugin.getDescription().getVersion();
        loginAttempts = 1;
        pluginName = locale.getPluginName();
        this.resourceId = resourceId;
        this.plugin = plugin;
        this.initiator = initiator;
        this.locale = locale;
        this.replace = replace;
        this.endTask = endTask;
    }

    protected static void init(JavaPlugin javaPlugin) {
        Logger logger = AutoUpdaterInternal.getLogger();

        UtilSpigotCreds.init();
        UtilEncryption.init();

        UtilThreading.async(() -> {
            if (!AutoUpdaterInternal.DEBUG) {
                try {
                    LogFactory.getFactory().setAttribute("org.apache.commons.logging.Log",
                            "org.apache.commons.logging.impl.NoOpLog");
                    Logger.getLogger("org.apache.commons.httpclient").setLevel(Level.OFF);
                } catch (Exception ex) {
                    AutoUpdaterInternal.get().printError(ex, "Unable to turn off HTMLUnit logging!.");
                }
            }

            //Setup web client
            webClient = new WebClient(BrowserVersion.CHROME);
            webClient.getOptions().setJavaScriptEnabled(true);
            webClient.getOptions().setTimeout(15000);
            webClient.getOptions().setCssEnabled(false);
            webClient.getOptions().setRedirectEnabled(true);
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webClient.getOptions().setThrowExceptionOnScriptError(false);
            webClient.getOptions().setPrintContentOnFailingStatusCode(false);
            if (!AutoUpdaterInternal.DEBUG)
                Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);

            logger.info("Initializing connection with Spigot...");

            //Spigot Site API
            siteAPI = new SpigotSiteCore();
            try {
                if (UtilSpigotCreds.getUsername() != null && UtilSpigotCreds.getPassword() != null) {
                    logger.info("Stored credentials detected, attempting login.");
                    new PremiumSpigotPluginUpdater(null, javaPlugin, 1, new UpdateLocale(), false).authenticate(false);
                }
            } catch (Exception ex) {
                AutoUpdaterInternal.get().printError(ex, "Error occurred while initializing the spigot site API.");
            }

            logger.info("Connected to Spigot.");
        });
    }

    protected static void resetUser() {
        currentUser = null;
        UtilSpigotCreds.reset();
    }

    @Override public String getLatestVersion() {
        try {
            return UtilReader.readFrom("https://api.spigotmc.org/legacy/update.php?resource=" + resourceId);
        } catch (Exception ex) {
            error(ex, "Error occurred while retrieving the latest version of a premium resource.");
            return null;
        }
    }

    @Override public String getDownloadUrlString() {
        return getResource().getDownloadURL();
    }

    /**
     * @implNote Makes a web request.
     */
    @Override public String[] getSupportedVersions() {
        try {
            JsonObject json = JSON_PARSER.parse(UtilReader.readFrom(SPIGET_BASE_URL + "resources/" + resourceId)).getAsJsonObject();
            JsonArray supportedVersionsObj = json.getAsJsonArray("testedVersions");
            List<String> supportedVersions = new ArrayList<>();
            supportedVersionsObj.forEach(e -> supportedVersions.add(e.getAsString()));
            return supportedVersions.toArray(new String[]{});
        } catch (IOException ex) {
            error(ex, "Error occurred while retrieving download URL of Spigot plugin.");
            return null;
        }
    }

    @Override public Boolean currentVersionSupported() {
        return Arrays.stream(getSupportedVersions()).anyMatch(ver -> Bukkit.getVersion().contains(ver));
    }

    @Override public Double getAverageRating() {
        return (double) getResource().getAverageRating();
    }

    @Override public void update() {
        startingTime = System.currentTimeMillis();
        UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[RETRIEVING PLUGIN INFO]", 10);

        UtilThreading.async(() -> {
            if (currentUser == null) {
                authenticate(true);
                UtilUI.sendActionBar(initiator, locale.getUpdating() + " &8[AUTHENTICATING SPIGOT ACCOUNT]", 10);
                return;
            }

            try {
                List<Resource> purchasedResources = siteAPI.getResourceManager().getPurchasedResources(currentUser);
                if (purchasedResources.stream().noneMatch(res -> res.getResourceId() == resourceId)) {
                    error(new ResourceNotPurchasedException("Error occurred while updating premium plugin."),
                            "The current spigot user has not purchased the plugin '" + pluginName + "'",
                            "YOU HAVE NOT BOUGHT THAT PLUGIN");
                    return;
                }

                String newVersion = getLatestVersion();
                locale.updateVariables(plugin.getName(), currentVersion, newVersion);

                if (currentVersion.equalsIgnoreCase(newVersion)) {
                    error(new NoUpdateFoundException("Error occurred while updating premium resource."), "Plugin already up to date.", "PLUGIN UP TO DATE");
                    return;
                }
            } catch (ConnectionFailedException | IllegalAccessException ex) {
                error(ex, "Error occurred while connecting to spigot.", "CONNECTION FAILED");
                return;
            }

            if (locale.getPluginName() != null)
                pluginName = locale.getPluginName();
            locale.setFileName(locale.getFileName());

            UtilUI.sendActionBar(initiator, locale.getUpdating() + " &8[ATTEMPTING DOWNLOAD]", 20);
            downloadResource();
        });
    }

    @Override public void downloadResource() {
        try {
            printDebug();
            Map<String, String> cookies = ((SpigotUser) currentUser).getCookies();

            cookies.forEach((key, value) -> webClient.getCookieManager().addCookie(
                    new Cookie("spigotmc.org", key, value)));

            webClient.waitForBackgroundJavaScript(10_000);
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            downloadUrl = getDownloadUrlString();
            Page page = webClient.getPage(downloadUrl);
            WebResponse response = page.getEnclosingWindow().getEnclosedPage().getWebResponse();

            if (page instanceof HtmlPage) {
                HtmlPage htmlPage = (HtmlPage) page;
                printDebug2(htmlPage);
                if (htmlPage.asXml().contains("DDoS protection by Cloudflare")) {
                    UtilUI.sendActionBar(initiator, locale.getUpdating() + " &8[WAITING FOR CLOUDFLARE]", 20);
                    webClient.waitForBackgroundJavaScript(10_000);
                }
                response = htmlPage.getEnclosingWindow().getEnclosedPage().getWebResponse();
            }

            String contentLength = response.getResponseHeaderValue("content-length");
            int completeFileSize = 0;
            int grabSize = 2048;
            if (contentLength != null)
                completeFileSize = Integer.parseInt(contentLength);

            printDebug1(page, response, webClient);

            BufferedInputStream in = new BufferedInputStream(response.getContentAsStream());
            FileOutputStream fos = new FileOutputStream(new File(pluginFolderPath + "/" + locale.getFileName() + ".jar"));
            BufferedOutputStream bout = new BufferedOutputStream(fos, grabSize);

            byte[] data = new byte[grabSize];
            long downloadedFileSize = 0;
            int grabbed;
            while ((grabbed = in.read(data, 0, grabSize)) >= 0) {
                downloadedFileSize += grabbed;

                //Don't send action bar for every grab we're not trying to crash any clients (or servers) here.
                if (downloadedFileSize % (grabSize * 5) == 0 && completeFileSize > 0) {
                    String bar = UtilUI.progressBar(15, downloadedFileSize, completeFileSize, ':', ChatColor.GREEN, ChatColor.RED);
                    final String currentPercent = String.format("%.2f", (((double) downloadedFileSize) / ((double) completeFileSize)) * 100);

                    UtilUI.sendActionBar(initiator, UtilUI.format(locale.getUpdatingDownload(),
                            "download_bar", bar,
                            "download_percent", currentPercent + "%")
                            + " &8[DOWNLOADING RESOURCE]");
                }
                bout.write(data, 0, grabbed);
            }

            bout.close();
            in.close();
            fos.close();

            printDebug3(downloadedFileSize, completeFileSize);
            initializePlugin();
        } catch (IOException ex) {
            error(ex, "Error occurred while updating premium resource.");
        }
    }

    @Override public void initializePlugin() {
        //Copy plugin utility from src/main/resources
        String corePluginFile = "/autoupdater-plugin-" + AutoUpdaterInternal.PROPERTIES.VERSION + ".jar";
        File targetFile = new File(AutoUpdaterInternal.getDataFolder().getParent() + corePluginFile);
        targetFile.getParentFile().mkdirs();
        try (InputStream is = getClass().getResourceAsStream(corePluginFile)) {
            Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            error(ex, ex.getMessage());
        }

        UtilThreading.sync(() -> {
            //Enable plugin and perform update task.
            try {
                UtilPlugin.loadPlugin(targetFile);
                if (Bukkit.getPluginManager().getPlugin("autoupdater-plugin") == null)
                    throw new FileNotFoundException("Unable to locate updater plugin.");

                UpdaterPlugin.get().updatePlugin(plugin, initiator, replace, pluginName,
                        pluginFolderPath, locale, startingTime, downloadUrl, resourceId, endTask);
            } catch (FileNotFoundException | InvalidPluginException | InvalidDescriptionException ex) {
                error(ex, ex.getMessage());
            }
        });
    }

    protected void authenticate(boolean recall) {
        UtilThreading.async(() -> {
            UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[ATTEMPTING DECRYPT]", 10);

            String username = UtilSpigotCreds.getUsername();
            String password = UtilSpigotCreds.getPassword();
            String twoFactor = UtilSpigotCreds.getTwoFactor();

            if (username == null || password == null) {
                runGuis(recall);
                return;
            }

            try {
                //Spare the extra request
                if (twoFactor != null)
                    throw new TwoFactorAuthenticationException();

                UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[ATTEMPTING AUTHENTICATION]", 15);
                currentUser = siteAPI.getUserManager().authenticate(username, password);

                if (currentUser == null) {
                    UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + "&c [INVALID CACHED CREDENTIALS]");
                    UtilSpigotCreds.clearFile();
                    runGuis(recall);
                    return;
                }

                UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[AUTHENTICATION SUCCESSFUL]");
                AutoUpdaterInternal.getLogger().info("Successfully logged in to Spigot as user '" + username + "'.");

                if (recall)
                    UtilThreading.sync(this::update);
            } catch (Exception firstException) {
                if (firstException instanceof TwoFactorAuthenticationException) {
                    try {
                        UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[ATTEMPTING 2FA AUTHENTICATION]", 15);
                        if (twoFactor == null) {
                            requestTwoFactor(username, password, recall);
                            return;
                        }

                        currentUser = siteAPI.getUserManager().authenticate(username, password, twoFactor);

                        if (currentUser == null) {
                            UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &c[INVALID CACHED CREDENTIALS]");
                            UtilSpigotCreds.clearFile();
                            runGuis(recall);
                            return;
                        }

                        UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[AUTHENTICATION SUCCESSFUL]");
                        AutoUpdaterInternal.getLogger().info("Successfully logged in to Spigot as user '" + username + "'.");

                        if (recall)
                            UtilThreading.sync(this::update);
                    } catch (Exception secondException) {
                        if (secondException instanceof InvalidCredentialsException) {
                            UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &c[INVALID CACHED CREDENTIALS]");
                            UtilSpigotCreds.clearFile();
                            runGuis(recall);
                        } else if (secondException instanceof ConnectionFailedException) {
                            error(firstException, "Error occurred while connecting to spigot.", "CONNECTION FAILED");
                        } else if (secondException instanceof TwoFactorAuthenticationException) {
                            if (currentUser != null) {
                                UtilThreading.sync(this::update);
                                return;
                            }

                            if (loginAttempts < 6) {
                                UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[RE-TRYING LOGIN IN 5s ATTEMPT #" + loginAttempts + "/5]", 15);
                                loginAttempts++;
                                UtilThreading.syncDelayed(() -> authenticate(recall), 100);
                            } else {
                                loginAttempts = 1;
                                error(secondException, "All login attempts failed.", "LOGIN FAILED");
                            }
                        } else {
                            error(secondException, "Error occurred while authenticating.");
                        }
                    }
                } else if (firstException instanceof InvalidCredentialsException) {
                    UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &c[INVALID CACHED CREDENTIALS]");
                    UtilSpigotCreds.clearFile();
                    runGuis(recall);
                } else if (firstException instanceof ConnectionFailedException) {
                    error(firstException, "Error occurred while connecting to spigot.", "CONNECTION FAILED");
                } else {
                    error(firstException, "Error occurred while authenticating.");
                }
            }
        });
    }

    /*
     * LOGIN UI
     */

    private void runGuis(boolean recall) {
        UtilThreading.sync(() -> {
            UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[RETRIEVING USERNAME]", 300);
            AtomicBoolean inputProvided = new AtomicBoolean(false);
            new AnvilGUI.Builder()
                    .text("Spigot username")
                    .plugin(AutoUpdaterInternal.getPlugin())
                    .onComplete((Player player, String usernameInput) -> {
                        inputProvided.set(true);
                        try {
                            if (siteAPI.getUserManager().getUserByName(usernameInput) != null) {
                                requestPassword(usernameInput, recall);
                            } else if (usernameInput.contains("@") && usernameInput.contains(".")) {
                                UtilUI.sendActionBar(initiator, "&cEmails aren't supported!", 10);
                                return AnvilGUI.Response.text("Emails aren't supported!");
                            } else {
                                UtilUI.sendActionBar(initiator, "&cInvalid username!", 10);
                                return AnvilGUI.Response.text("Invalid username!");
                            }
                        } catch (ConnectionFailedException ex) {
                            initiator.closeInventory();
                            error(ex, "Error occurred while connecting to Spigot.", "CONNECTION FAILED");
                            return AnvilGUI.Response.text("Error occurred.");
                        }

                        return AnvilGUI.Response.text("Success!");
                    }).onClose(player -> {
                if (!inputProvided.get())
                    error(new UserExitException("Error occurred while retrieving user input."), "User closed GUI.", "GUI CLOSED");
            }).open(initiator);
        });
    }

    private void requestPassword(String usernameInput, boolean recall) {
        UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[RETRIEVING PASSWORD]", 300);
        AtomicBoolean inputProvided = new AtomicBoolean(false);
        new AnvilGUI.Builder()
                .text("Spigot password")
                .plugin(AutoUpdaterInternal.getPlugin())
                .onComplete((player, passwordInput) -> {
                    inputProvided.set(true);
                    try {
                        currentUser = siteAPI.getUserManager().authenticate(usernameInput, passwordInput);

                        if (currentUser == null)
                            throw new InvalidCredentialsException();

                        UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[ENCRYPTING CREDENTIALS]", 10);
                        UtilSpigotCreds.setUsername(usernameInput);
                        UtilSpigotCreds.setPassword(passwordInput);
                        UtilSpigotCreds.saveFile();

                        if (recall)
                            UtilThreading.syncDelayed(this::update, 50);
                    } catch (TwoFactorAuthenticationException ex) {
                        requestTwoFactor(usernameInput, passwordInput, recall);
                    } catch (ConnectionFailedException ex) {
                        initiator.closeInventory();
                        error(ex, "Error occurred while connecting to Spigot.", "CONNECTION FAILED");
                        return AnvilGUI.Response.text("Could not connect to Spigot.");
                    } catch (InvalidCredentialsException ex) {
                        return AnvilGUI.Response.text("Invalid password.");
                    }

                    return AnvilGUI.Response.text("Success!");
                }).onClose(player -> {
            if (!inputProvided.get())
                error(new UserExitException("Error occurred while retrieving user input."), "User closed GUI.", "GUI CLOSED");
        }).open(initiator);
    }

    private void requestTwoFactor(String usernameInput, String passwordInput, boolean recall) {
        UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[RETRIEVING TWO FACTOR SECRET]", 600);
        AtomicBoolean inputProvided = new AtomicBoolean(false);
        new AnvilGUI.Builder().plugin(AutoUpdaterInternal.getPlugin())
                .text("Spigot 2FA secret")
                .onComplete((Player player, String twoFactorInput) -> {
                    inputProvided.set(true);
                    try {
                        currentUser = siteAPI.getUserManager().authenticate(usernameInput, passwordInput, twoFactorInput);
                        UtilUI.sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[ENCRYPTING CREDENTIALS]", 10);

                        if (currentUser == null)
                            throw new InvalidCredentialsException();

                        UtilSpigotCreds.setUsername(usernameInput);
                        UtilSpigotCreds.setPassword(passwordInput);
                        UtilSpigotCreds.setTwoFactor(twoFactorInput);
                        UtilSpigotCreds.saveFile();

                        if (recall)
                            UtilThreading.syncDelayed(this::update, 50);
                        return AnvilGUI.Response.text("Logging in, close GUI.");
                    } catch (InvalidCredentialsException | TwoFactorAuthenticationException ex) {
                        return AnvilGUI.Response.text("Invalid key.");
                    } catch (Exception ex) {
                        initiator.closeInventory();
                        error(ex, "Error occurred while authenticating Spigot user.");
                        return AnvilGUI.Response.text("Authentication failed.");
                    }
                }).onClose(player -> {
            if (!inputProvided.get())
                error(new UserExitException("Error occurred while retrieving user input."), "User closed GUI.", "GUI CLOSED");
        }).open(initiator);
    }

    private Resource getResource() {
        if (resource == null) {
            try {
                resource = siteAPI.getResourceManager().getResourceById(resourceId, currentUser);
            } catch (ConnectionFailedException ex) {
                error(ex, "Error occurred while retrieving the download url of a premium resource.");
            }
        }

        return resource;
    }

    /*
     * ERROR HANDLING
     */

    private void error(Exception ex, String message) {
        if (AutoUpdaterInternal.DEBUG)
            AutoUpdaterInternal.get().printError(ex, message);

        UtilUI.sendActionBar(initiator, locale.getUpdateFailed() + " &8[CHECK CONSOLE]");
        endTask.run(false, ex, null, pluginName);
    }

    private void error(Exception ex, String errorMessage, String shortErrorMessage) {
        if (AutoUpdaterInternal.DEBUG)
            AutoUpdaterInternal.get().printError(ex, errorMessage);

        UtilUI.sendActionBar(initiator, locale.getUpdateFailed() + " &8[" + shortErrorMessage + "&8]", 10);
        endTask.run(false, ex, null, pluginName);
    }

    /*
     * DEBUG MESSAGES - Messy code ahead.
     */

    @SuppressWarnings("DuplicatedCode")
    private void printDebug() {
        if (AutoUpdaterInternal.DEBUG) {
            AutoUpdaterInternal.getLogger().info("\n\n\n\n\n\n============== BEGIN PREMIUM PLUGIN DEBUG ==============");
            AutoUpdaterInternal.getLogger().info("AUTHENTICATED: " + currentUser.isAuthenticated());
            AutoUpdaterInternal.getLogger().info("COOKIES: ");
            ((SpigotUser) currentUser).getCookies().forEach((k, v) -> AutoUpdaterInternal.getLogger().info("\t" + k + " | " + v));
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private void printDebug1(Page page, WebResponse response, WebClient webClient) {
        if (AutoUpdaterInternal.DEBUG) {
            if (pluginName != null)
                AutoUpdaterInternal.getLogger().info("PLUGIN = " + pluginName);

            if (page instanceof HtmlPage) {
                AutoUpdaterInternal.getLogger().info("\n\nPAGETYPE = HtmlPage");
                HtmlPage htmlPage = (HtmlPage) page;

                AutoUpdaterInternal.getLogger().info("PREVIOUS STATUS CODE = " + htmlPage.getWebResponse().getStatusCode());
                AutoUpdaterInternal.getLogger().info("HISTORY: ");
                for (int i = 0; i < page.getEnclosingWindow().getHistory().getLength(); i++) {
                    AutoUpdaterInternal.getLogger().info(htmlPage.getEnclosingWindow().getHistory().getUrl(i).toString());
                }
                AutoUpdaterInternal.getLogger().info("STATUS CODE = " + htmlPage.getEnclosingWindow().getEnclosedPage().getWebResponse().getStatusCode());
                htmlPage.getEnclosingWindow().getEnclosedPage().getWebResponse().getResponseHeaders().forEach(nvpair -> AutoUpdaterInternal.getLogger().info(nvpair.getName() + " | " + nvpair.getValue()));
            } else if (page instanceof UnexpectedPage) {
                AutoUpdaterInternal.getLogger().info("\n\nPAGETYPE = UnexpectedPage");
                UnexpectedPage unexpectedPage = (UnexpectedPage) page;
                AutoUpdaterInternal.getLogger().info("PREVIOUS STATUS CODE = " + unexpectedPage.getWebResponse().getStatusCode());
                AutoUpdaterInternal.getLogger().info("HISTORY: ");
                for (int i = 0; i < page.getEnclosingWindow().getHistory().getLength(); i++) {
                    AutoUpdaterInternal.getLogger().info(page.getEnclosingWindow().getHistory().getUrl(i).toString());
                }
                AutoUpdaterInternal.getLogger().info("STATUS CODE = " + unexpectedPage.getEnclosingWindow().getEnclosedPage().getWebResponse().getStatusCode());
                AutoUpdaterInternal.getLogger().info("NAME | VALUE");
                unexpectedPage.getEnclosingWindow().getEnclosedPage().getWebResponse().getResponseHeaders().forEach(nvpair -> AutoUpdaterInternal.getLogger().info(nvpair.getName() + " | " + nvpair.getValue()));
            }

            AutoUpdaterInternal.getLogger().info("\n\nPAGETYPE = Page");
            AutoUpdaterInternal.getLogger().info("HISTORY: ");
            for (int i = 0; i < page.getEnclosingWindow().getHistory().getLength(); i++) {
                AutoUpdaterInternal.getLogger().info(page.getEnclosingWindow().getHistory().getUrl(i).toString());
            }
            AutoUpdaterInternal.getLogger().info("PREVIOUS STATUS CODE = " + page.getWebResponse().getStatusCode());
            AutoUpdaterInternal.getLogger().info("STATUS CODE = " + response.getStatusCode());
            AutoUpdaterInternal.getLogger().info("CONTENT TYPE = " + response.getContentType());
            AutoUpdaterInternal.getLogger().info("STATUS MESSAGE = " + response.getStatusMessage());
            AutoUpdaterInternal.getLogger().info("LOAD TIME = " + response.getLoadTime());
            AutoUpdaterInternal.getLogger().info("CURRENT URL = " + webClient.getCurrentWindow().getEnclosedPage().getUrl());
            AutoUpdaterInternal.getLogger().info("NAME | VALUE");
            response.getResponseHeaders().forEach(nvpair -> AutoUpdaterInternal.getLogger().info(nvpair.getName() + " | " + nvpair.getValue()));
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private void printDebug2(HtmlPage htmlPage) {
        if (AutoUpdaterInternal.DEBUG) {
            AutoUpdaterInternal.getLogger().info("---- EARLY HTML OUTPUT ----");
            AutoUpdaterInternal.getLogger().info("\nPAGETYPE = HtmlPage");

            AutoUpdaterInternal.getLogger().info("PREVIOUS STATUS CODE = " + htmlPage.getWebResponse().getStatusCode());
            AutoUpdaterInternal.getLogger().info("HISTORY: ");
            for (int i = 0; i < htmlPage.getEnclosingWindow().getHistory().getLength(); i++) {
                AutoUpdaterInternal.getLogger().info(htmlPage.getEnclosingWindow().getHistory().getUrl(i).toString());
            }
            AutoUpdaterInternal.getLogger().info("PREVIOUS STATUS CODE = " + htmlPage.getWebResponse().getStatusCode());
            AutoUpdaterInternal.getLogger().info("STATUS CODE = " + htmlPage.getEnclosingWindow().getEnclosedPage().getWebResponse().getStatusCode());
            htmlPage.getEnclosingWindow().getEnclosedPage().getWebResponse().getResponseHeaders().forEach(nvpair -> AutoUpdaterInternal.getLogger().info(nvpair.getName() + " | " + nvpair.getValue()));
            AutoUpdaterInternal.getLogger().info("\n---- EARLY HTML OUTPUT ----");
        }
    }

    private void printDebug3(long downloadedFileSize, int completeFileSize) {
        if (AutoUpdaterInternal.DEBUG) {
            AutoUpdaterInternal.getLogger().info("FINISHED WITH " + downloadedFileSize + "/" + completeFileSize + " (" + String.format("%.2f", (((double) downloadedFileSize) / ((double) completeFileSize)) * 100) + "%)");
            AutoUpdaterInternal.getLogger().info("============== END PREMIUM PLUGIN DEBUG =======");
        }
    }
}