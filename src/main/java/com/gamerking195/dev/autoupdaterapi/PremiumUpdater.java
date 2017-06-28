package com.gamerking195.dev.autoupdaterapi;

import be.maximvdw.spigotsite.api.exceptions.ConnectionFailedException;
import be.maximvdw.spigotsite.api.resource.Resource;
import be.maximvdw.spigotsite.api.user.User;
import be.maximvdw.spigotsite.api.user.exceptions.InvalidCredentialsException;
import be.maximvdw.spigotsite.api.user.exceptions.TwoFactorAuthenticationException;
import be.maximvdw.spigotsite.user.SpigotUser;
import com.gamerking195.dev.autoupdaterapi.util.UtilPlugin;
import com.gamerking195.dev.autoupdaterapi.util.UtilSpigotCreds;
import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.util.Cookie;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.util.Map;

public class PremiumUpdater {
    private Player initiator;

    private JavaPlugin plugin;

    private String dataFolderPath;
    private String currentVersion;
    private String pluginName;

    private User spigotUser;
    private Resource resource;

    private UpdateLocale locale;

    private boolean deleteUpdater;
    private boolean deleteOld;

    private int resourceId;

    /**
     * Instantiate PremiumUpdater
     *
     * @param initiator     The player that started this action (if there is none set to null).
     * @param plugin        The instance of the outdated plugin.
     * @param resourceId    The ID of the plugin on Spigot found in the url after the name.
     * @param locale        The locale file you want containing custom messages. Note most messages will be followed with a progress indicator like [DOWNLOADING].
     * @param deleteUpdater Should the updater delete itself after the update fails / succeeds.
     * @param deleteOld     Should the old version of the plugin be deleted & disabled.
     */
    public PremiumUpdater(Player initiator, JavaPlugin plugin, int resourceId, UpdateLocale locale, boolean deleteUpdater, boolean deleteOld) {
        spigotUser = AutoUpdaterAPI.getInstance().getCurrentUser();
        dataFolderPath = AutoUpdaterAPI.getInstance().getDataFolder().getPath();
        currentVersion = plugin.getDescription().getVersion();
        pluginName = locale.getPluginName();
        this.resourceId = resourceId;
        this.plugin = plugin;
        this.initiator = initiator;
        this.locale = locale;
        this.deleteUpdater = deleteUpdater;
        this.deleteOld = deleteOld;
    }

    /**
     * Updates the plugin.
     */
    public void update() {
        sendActionBarSync(initiator, locale.getUpdatingNoVar() + " &8[RETRIEVING PLUGIN INFO]");

        spigotUser = AutoUpdaterAPI.getInstance().getCurrentUser();

        if (spigotUser == null) {
            authenticate();
            sendActionBarSync(initiator, locale.getUpdatingNoVar() + " &8[AUTHENTICATING SPIGOT ACCOUNT]");
            return;
        }

        try {
            for (Resource resource : AutoUpdaterAPI.getInstance().getApi().getResourceManager().getPurchasedResources(spigotUser)) {
                if (resource.getResourceId() == resourceId) {
                    this.resource = resource;
                }
            }
        } catch (ConnectionFailedException ex) {
            sendActionBarSync(initiator, locale.getUpdateFailedNoVar());
            AutoUpdaterAPI.getInstance().printError(ex, "Error occurred while connecting to spigot. (#1)");
            delete();
        }

        if (resource == null) {
            AutoUpdaterAPI.getInstance().printPluginError("Error occurred while updating " + pluginName + "!", "That plugin has not been bought by the current user!");
            sendActionBarSync(initiator, "&c&lUPDATE FAILED &8[YOU HAVE NOT BOUGHT THAT PLUGIN]");
            delete();
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String newVersion = AutoUpdaterAPI.getInstance().getApi().getResourceManager().getResourceById(resourceId, spigotUser).getLastVersion();

                    if (newVersion.equals(currentVersion)) {
                        sendActionBar(initiator, locale.getUpdateFailed().replace("%plugin%", pluginName).replace("%old_version%", currentVersion).replace("%new_version%", newVersion) + " &8[NO UPDATE AVAILABLE]");
                        delete();
                        return;
                    }

                    sendActionBar(initiator, locale.getUpdating().replace("%plugin%", pluginName).replace("%old_version%", currentVersion).replace("%new_version%", newVersion) + " &8[ATTEMPTING DOWNLOAD]");

                    Map<String, String> cookies = ((SpigotUser) spigotUser).getCookies();

                    WebClient webClient = AutoUpdaterAPI.getInstance().getWebClient();

                    for (Map.Entry<String, String> entry : cookies.entrySet())
                        webClient.getCookieManager().addCookie(new Cookie("spigotmc.org", entry.getKey(), entry.getValue()));

                    webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);

                    HtmlPage htmlPage = webClient.getPage(AutoUpdaterAPI.getInstance().getApi().getResourceManager().getResourceById(resourceId, spigotUser).getDownloadURL());

                    webClient.waitForBackgroundJavaScript(10_000);

                    Integer completeFileSize = Integer.valueOf(htmlPage.getEnclosingWindow().getEnclosedPage().getWebResponse().getResponseHeaderValue("Content-Length"));

                    BufferedInputStream in = new java.io.BufferedInputStream(htmlPage.getEnclosingWindow().getEnclosedPage().getWebResponse().getContentAsStream());
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(dataFolderPath.substring(0, dataFolderPath.lastIndexOf("/")) + "/" + locale.getFileName() + ".jar"));
                    java.io.BufferedOutputStream bout = new BufferedOutputStream(fos, 1024);

                    byte[] data = new byte[1024];
                    long downloadedFileSize = 0;
                    int x;
                    while ((x = in.read(data, 0, 1024)) >= 0) {
                        downloadedFileSize += x;

                        //Don't send action bar for every byte of data we're not trying to crash any clients (or servers) here.
                        if (downloadedFileSize % 10000 == 0) {
                            final int currentProgress = (int) ((((double) downloadedFileSize) / ((double) completeFileSize)) * 15);

                            final String currentPercent = String.format("%.2f", (((double) downloadedFileSize) / ((double) completeFileSize)) * 100);

                            String bar = "&a:::::::::::::::";

                            bar = bar.substring(0, currentProgress + 2) + "&c" + bar.substring(currentProgress + 2);

                            sendActionBar(initiator, locale.getUpdatingDownload().replace("%plugin%", pluginName).replace("%old_version%", currentVersion).replace("%new_version%", newVersion).replace("%download_bar%", bar).replace("%download_percent%", currentPercent + "%") + " &8[DOWNLOADING RESOURCE]");
                        }

                        bout.write(data, 0, x);
                    }

                    bout.close();
                    in.close();

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            try {
                                AutoUpdaterAPI.getInstance().setWebClient(new WebClient(BrowserVersion.CHROME));

                                if (deleteOld) {
                                    UtilPlugin.unload(plugin);

                                    if (!new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).delete())
                                        AutoUpdaterAPI.getInstance().printPluginError("Error occurred while updating " + pluginName + ".", "Could not deleteUpdater old plugin jar.");
                                }

                                sendActionBar(initiator, locale.getUpdating().replace("%plugin%", pluginName).replace("%old_version%", currentVersion).replace("%new_version%", newVersion) + " &8[INITIALIZING]");

                                Bukkit.getPluginManager().loadPlugin(new File(dataFolderPath.substring(0, dataFolderPath.lastIndexOf("/")) + "/" + locale.getFileName() + ".jar"));
                                Bukkit.getPluginManager().enablePlugin(Bukkit.getPluginManager().getPlugin(pluginName));

                                sendActionBar(initiator, locale.getUpdateComplete().replace("%plugin%", pluginName).replace("%old_version%", currentVersion).replace("%new_version%", newVersion));

                                delete();
                            } catch (Exception ex) {
                                sendActionBar(initiator, locale.getUpdateFailedNoVar());
                                AutoUpdaterAPI.getInstance().printError(ex, "Error occurred while updating premium resource.");
                                delete();
                            }
                        }
                    }.runTask(AutoUpdaterAPI.getInstance());
                } catch (Exception ex) {
                    sendActionBar(initiator, locale.getUpdateFailedNoVar());
                    AutoUpdaterAPI.getInstance().printError(ex, "Error occurred while updating premium resource.");
                    delete();
                }
            }
        }.runTaskAsynchronously(AutoUpdaterAPI.getInstance());
    }

    private void authenticate() {
        new BukkitRunnable() {
            @Override
            public void run() {
                sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[ATTEMPTING DECRYPT]");

                String username = UtilSpigotCreds.getInstance().getUsername();
                String password = UtilSpigotCreds.getInstance().getPassword();
                String twoFactor = UtilSpigotCreds.getInstance().getTwoFactor();

                if (username == null || password == null) {
                    runGuis();
                    return;
                }

                try {
                    sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[ATTEMPTING AUTHENTICATION]");
                    spigotUser = AutoUpdaterAPI.getInstance().getApi().getUserManager().authenticate(username, password);
                    AutoUpdaterAPI.getInstance().setCurrentUser(spigotUser);


                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            try {
                                update();
                            } catch (Exception ex) {
                                AutoUpdaterAPI.getInstance().printError(ex);
                            }
                        }
                    }.runTaskLater(AutoUpdaterAPI.getInstance(), 40L);

                } catch (TwoFactorAuthenticationException ex) {
                    try {
                        sendActionBar(initiator, locale.getUpdatingNoVar() + " &8[RE-ATTEMPTING AUTHENTICATION]");
                        if (twoFactor == null) {
                            runGuis();
                            return;
                        }

                        spigotUser = AutoUpdaterAPI.getInstance().getApi().getUserManager().authenticate(username, password, twoFactor);
                        AutoUpdaterAPI.getInstance().setCurrentUser(spigotUser);


                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                try {
                                    update();
                                } catch (Exception ex) {
                                    AutoUpdaterAPI.getInstance().printError(ex);
                                }
                            }
                        }.runTask(AutoUpdaterAPI.getInstance());
                    } catch (Exception exception) {
                        runGuis();
                    }
                } catch (InvalidCredentialsException ex) {
                    sendActionBar(initiator, locale.getUpdatingNoVar() + " &c[INVALID CACHED CREDENTIALS]");
                    runGuis();
                } catch (ConnectionFailedException ex) {
                    sendActionBar(initiator, locale.getUpdateFailedNoVar());
                    AutoUpdaterAPI.getInstance().printError(ex, "Error occurred while connecting to spigot. (#2)");
                    delete();
                }
            }
        }.runTaskAsynchronously(AutoUpdaterAPI.getInstance());
    }

    private void runGuis() {
        new BukkitRunnable() {
            @Override
            public void run() {
                sendActionBarSync(initiator, locale.getUpdatingNoVar() + " &8[RETRIEVING USERNAME]");
                new AnvilGUI(AutoUpdaterAPI.getInstance(), initiator, "Spigot username", (Player player1, String usernameInput) -> {
                    try {
                        if (AutoUpdaterAPI.getInstance().getApi().getUserManager().getUserByName(usernameInput) != null) {
                            sendActionBarSync(initiator, locale.getUpdatingNoVar() + " &8[RETRIEVING PASSWORD]");
                            new AnvilGUI(AutoUpdaterAPI.getInstance(), initiator, "Spigot password", (Player player2, String passwordInput) -> {
                                try {
                                    spigotUser = AutoUpdaterAPI.getInstance().getApi().getUserManager().authenticate(usernameInput, passwordInput);

                                    sendActionBarSync(initiator, locale.getUpdatingNoVar() + " &8[ENCRYPTING CREDENTIALS]");
                                    UtilSpigotCreds.getInstance().setUsername(usernameInput);
                                    UtilSpigotCreds.getInstance().setPassword(passwordInput);
                                    UtilSpigotCreds.getInstance().saveFile();

                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            authenticate();
                                        }
                                    }.runTaskLater(AutoUpdaterAPI.getInstance(), 100L);
                                    player2.closeInventory();

                                } catch (TwoFactorAuthenticationException ex) {
                                    sendActionBarSync(initiator, locale.getUpdatingNoVar() + " &8[RETRIEVING TWO FACTOR SECRET]");
                                    new AnvilGUI(AutoUpdaterAPI.getInstance(), initiator, "Spigot two factor secret", (Player player3, String twoFactorInput) -> {
                                        try {
                                            //Make extra string because the input seems to change for some reason.
                                            final String twoFactorSecret = twoFactorInput;
                                            spigotUser = AutoUpdaterAPI.getInstance().getApi().getUserManager().authenticate(usernameInput, passwordInput, twoFactorSecret);

                                            sendActionBarSync(initiator, locale.getUpdatingNoVar() + " &8[ENCRYPTING CREDENTIALS]");

                                            UtilSpigotCreds.getInstance().setUsername(usernameInput);
                                            UtilSpigotCreds.getInstance().setPassword(passwordInput);
                                            UtilSpigotCreds.getInstance().setTwoFactor(twoFactorSecret);
                                            UtilSpigotCreds.getInstance().saveFile();

                                            new BukkitRunnable() {
                                                @Override
                                                public void run() {
                                                    authenticate();
                                                }
                                            }.runTaskLater(AutoUpdaterAPI.getInstance(), 100L);
                                            player3.closeInventory();

                                            return "Retrieved credentials you may now close this GUI.";
                                        } catch (Exception exception) {
                                            sendActionBarSync(initiator, locale.getUpdateFailedNoVar());
                                            AutoUpdaterAPI.getInstance().printError(exception, "Error occurred while authenticating Spigot user.");
                                            delete();
                                            return "Authentication failed";
                                        }
                                    });
                                } catch (ConnectionFailedException ex) {
                                    sendActionBarSync(initiator, locale.getUpdateFailedNoVar());
                                    AutoUpdaterAPI.getInstance().printError(ex, "Error occurred while connecting to Spigot. (#3)");
                                    delete();
                                    return "Could not connect to Spigot";
                                } catch (InvalidCredentialsException ex) {
                                    sendActionBarSync(initiator, locale.getUpdateFailedNoVar());
                                    delete();
                                    return "Invalid credentials";
                                }

                                return null;
                            });
                        } else {
                            sendActionBarSync(initiator, locale.getUpdateFailedNoVar());
                            delete();
                            return "Invalid username!";
                        }
                    } catch (Exception ex) {
                        AutoUpdaterAPI.getInstance().printError(ex, "Error occurred while authenticating Spigot username.");
                        sendActionBarSync(initiator, locale.getUpdateFailedNoVar());
                        delete();
                    }

                    return null;
                });
            }
        }.runTask(AutoUpdaterAPI.getInstance());
    }

    /*
     * Utilities
     */

    private void sendActionBar(Player player, String message) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player != null)
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.translateAlternateColorCodes('&', message)));
                if (AutoUpdaterAPI.getInstance().isDebug())
                    AutoUpdaterAPI.getInstance().getLogger().info(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message)));

            }
        }.runTask(AutoUpdaterAPI.getInstance());
    }

    //Don't switch threads & schedule a task if you don't have to.
    private void sendActionBarSync(Player player, String message) {
        if (player != null)
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.translateAlternateColorCodes('&', message)));
        if (AutoUpdaterAPI.getInstance().isDebug())
            AutoUpdaterAPI.getInstance().getLogger().info(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message)));

    }

    private void delete() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (deleteUpdater) {
                    try {
                        if (!new File(AutoUpdaterAPI.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).delete()) {
                            AutoUpdaterAPI.getInstance().printPluginError("Error occurred while updating " + pluginName + ".", "Could not delete updater jar.");
                        }

                        UtilPlugin.unload(AutoUpdaterAPI.getInstance());
                    } catch (Exception ex) {
                        AutoUpdaterAPI.getInstance().printError(ex);
                    }
                }
            }
        }.runTask(AutoUpdaterAPI.getInstance());
    }
}