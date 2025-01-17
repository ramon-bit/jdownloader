//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.config.SubConfiguration;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;
import jd.utils.locale.JDL;

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.os.CrossSystem;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "fileboom.me" }, urls = { "http://(www\\.)?(fboom|fileboom)\\.me/file/[a-z0-9]{13,}" }, flags = { 2 })
public class FileBoomMe extends PluginForHost {

    public FileBoomMe(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://fboom.me/premium.html");
        setConfigElements();
    }

    @Override
    public String getAGBLink() {
        return "http://fboom.me/page/terms.html";
    }

    public void correctDownloadLink(final DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("fileboom.me/", "fboom.me/"));
    }

    private static AtomicInteger maxPrem        = new AtomicInteger(1);
    /* User settings */
    private static final String  USE_API        = "USE_API";
    private final static String  SSL_CONNECTION = "SSL_CONNECTION";

    /* api stuff */
    private PluginForHost        k2sPlugin      = null;

    private void pluginLoaded() throws PluginException {
        if (k2sPlugin == null) {
            k2sPlugin = JDUtilities.getPluginForHost("keep2share.cc");
            if (k2sPlugin == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
    }

    /* dl stuff */
    private boolean isFree;
    private boolean resumes;
    private String  directlinkproperty;
    private int     chunks;

    private void setConstants(final Account account) {
        if (account != null) {
            if (account.getBooleanProperty("free", false)) {
                // free account
                chunks = 1;
                resumes = true;
                isFree = true;
                directlinkproperty = "freelink2";
            } else {
                // premium account
                chunks = 0;
                resumes = true;
                isFree = false;
                directlinkproperty = "premlink";
            }
            logger.finer("setConstants = " + account.getUser() + " @ Account Download :: isFree = " + isFree + ", upperChunks = " + chunks + ", Resumes = " + resumes);
        } else {
            // free non account
            chunks = 1;
            resumes = true;
            isFree = true;
            directlinkproperty = "freelink1";
            logger.finer("setConstants = Guest Download :: isFree = " + isFree + ", upperChunks = " + chunks + ", Resumes = " + resumes);
        }
    }

    private boolean apiEnabled() {
        return false;
        // this.getPluginConfig().getBooleanProperty(USE_API, false);
    }

    private void setConfigElements() {
        // this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), USE_API,
        // JDL.L("plugins.hoster.Keep2ShareCc.useAPI",
        // "Use API (recommended!)\r\nIMPORTANT: Free accounts will still be accepted in API mode but they can not be used!")).setDefaultValue(true));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), SSL_CONNECTION, JDL.L("plugins.hoster.Keep2ShareCc.preferSSL", "Use Secure Communication over SSL (HTTPS://)")).setDefaultValue(false));
    }

    public boolean checkLinks(final DownloadLink[] urls) {
        try {
            if (this.apiEnabled()) {
                pluginLoaded();
                final jd.plugins.hoster.Keep2ShareCc.kfpAPI api = ((jd.plugins.hoster.Keep2ShareCc) k2sPlugin).kfpAPI("fileboom.me");
                return api.checkLinks(urls);
            } else {
                return false;
            }
        } catch (final Exception e) {
            return false;
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.getPage(link.getDownloadURL());
        if (br.getRequest().getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String filename = br.getRegex("<i class=\"icon\\-download\"></i>([^<>\"]*?)</").getMatch(0);
        final String filesize = br.getRegex(">File size: ([^<>\"]*?)</").getMatch(0);
        if (filename == null || filesize == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setName(Encoding.htmlDecode(filename.trim()));
        link.setDownloadSize(SizeFormatter.getSize(filesize));
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        setConstants(null);
        checkShowFreeDialog();
        if (this.apiEnabled()) {
            pluginLoaded();
            final jd.plugins.hoster.Keep2ShareCc.kfpAPI api = ((jd.plugins.hoster.Keep2ShareCc) k2sPlugin).kfpAPI("fileboom.me");
            api.setChunks(chunks);
            api.setResumes(resumes);
            api.setDirectlinkproperty(directlinkproperty);
            api.setDl(dl);
            api.handleDownload(downloadLink, null);
        } else {
            requestFileInformation(downloadLink);
            doFree(downloadLink, null);
        }
    }

    private final String freeAccConLimit = "Free account does not allow to download more than one file at the same time";
    private final String reCaptcha       = "api\\.recaptcha\\.net|google\\.com/recaptcha/api/";
    private final String formCaptcha     = "/file/captcha\\.html\\?v=[a-z0-9]+";

    public void doFree(final DownloadLink downloadLink, final Account account) throws Exception, PluginException {
        String dllink = checkDirectLink(downloadLink, "directlink");
        dllink = getDllink();
        if (dllink == null) {
            if (br.containsHTML(">\\s*This file is available<br>only for premium members\\.\\s*</div>")) {
                freeDlLimitation();
            }
            final String id = br.getRegex("data\\-slow\\-id=\"([a-z0-9]+)\"").getMatch(0);
            if (id == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            br.postPage(br.getURL(), "slow_id=" + id);
            if (br.containsHTML("Free user can\\'t download large files")) {
                freeDlLimitation();
            } else if (br.containsHTML(freeAccConLimit)) {
                // could be shared network or a download hasn't timed out yet or user downloading in another program?
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Connection limit reached", 10 * 60 * 60 * 1001);
            }
            if (br.containsHTML(">Downloading is not possible<")) {
                final Regex waittime = br.getRegex("Please wait (\\d{2}):(\\d{2}):(\\d{2}) to download this");
                String tmphrs = waittime.getMatch(0);
                String tmpmin = waittime.getMatch(1);
                String tmpsec = waittime.getMatch(2);
                if (tmphrs == null && tmpmin == null && tmpsec == null) {
                    logger.info("Waittime regexes seem to be broken");
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 60 * 60 * 1000l);
                } else {
                    int minutes = 0, seconds = 0, hours = 0;
                    if (tmphrs != null) {
                        hours = Integer.parseInt(tmphrs);
                    }
                    if (tmpmin != null) {
                        minutes = Integer.parseInt(tmpmin);
                    }
                    if (tmpsec != null) {
                        seconds = Integer.parseInt(tmpsec);
                    }
                    int totalwaittime = ((3600 * hours) + (60 * minutes) + seconds + 1) * 1000;
                    logger.info("Detected waittime #2, waiting " + waittime + "milliseconds");
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, totalwaittime);
                }
            }
            dllink = getDllink();
            if (dllink == null) {
                final int repeat = 4;
                for (int i = 1; i <= repeat; i++) {
                    if (br.containsHTML(reCaptcha)) {
                        final PluginForHost recplug = JDUtilities.getPluginForHost("DirectHTTP");
                        final jd.plugins.hoster.DirectHTTP.Recaptcha rc = ((DirectHTTP) recplug).getReCaptcha(br);
                        rc.findID();
                        rc.load();
                        final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                        final String c = getCaptchaCode(cf, downloadLink);
                        br.postPage(br.getURL(), "recaptcha_challenge_field=" + rc.getChallenge() + "&recaptcha_response_field=" + Encoding.urlEncode(c) + "&free=1&freeDownloadRequest=1&uniqueId=" + id);
                        if (br.containsHTML(reCaptcha) && i + 1 != repeat) {
                            continue;
                        } else if (br.containsHTML(reCaptcha) && i + 1 == repeat) {
                            throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                        } else {
                            break;
                        }
                    } else if (br.containsHTML(formCaptcha)) {
                        String captcha = br.getRegex(formCaptcha).getMatch(-1);
                        String code = getCaptchaCode(captcha, downloadLink);
                        br.postPage(br.getURL(), "CaptchaForm%5Bcode%5D=" + code + "&free=1&freeDownloadRequest=1&uniqueId=" + id);
                        if (br.containsHTML(formCaptcha) && i + 1 != repeat) {
                            continue;
                        } else if (br.containsHTML(formCaptcha) && i + 1 == repeat) {
                            throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                        } else {
                            break;
                        }
                    }
                }
                int wait = 30;
                final String waittime = br.getRegex("class=\"tik\\-tak\">(\\d+)</div>").getMatch(0);
                if (waittime != null) {
                    wait = Integer.parseInt(waittime);
                }
                this.sleep(wait * 1001l, downloadLink);
                br.postPage(br.getURL(), "free=1&uniqueId=" + id);
                if (br.containsHTML(freeAccConLimit)) {
                    // could be shared network or a download hasn't timed out yet or user downloading in another program?
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Connection limit reached", 10 * 60 * 60 * 1001);
                }
                dllink = getDllink();
                if (dllink == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumes, chunks);
        if (dl.getConnection().getResponseCode() == 401) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 401", 30 * 60 * 1000l);
        }
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setProperty("directlink", dllink);
        dl.startDownload();
    }

    private void freeDlLimitation() throws PluginException {
        try {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } catch (final Throwable e) {
            if (e instanceof PluginException) {
                throw (PluginException) e;
            }
        }
        throw new PluginException(LinkStatus.ERROR_FATAL, "This file can only be downloaded by premium users");
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            try {
                final Browser br2 = br.cloneBrowser();
                URLConnectionAdapter con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
                con.disconnect();
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            }
        }
        return dllink;
    }

    private static final String MAINPAGE = "http://fboom.me";
    private static Object       LOCK     = new Object();

    @SuppressWarnings("unchecked")
    private void login(final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                // Load cookies
                br.setCookiesExclusive(true);
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof HashMap<?, ?> && !force) {
                    final HashMap<String, String> cookies = (HashMap<String, String>) ret;
                    if (account.isValid()) {
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            br.setCookie(MAINPAGE, key, value);
                        }
                        return;
                    }
                }
                br.setFollowRedirects(false);
                br.getPage("http://fboom.me/login.html");
                String logincaptcha = br.getRegex("\"(/auth/captcha\\.html[^<>\"]*?)\"").getMatch(0);
                String postData = "LoginForm%5BrememberMe%5D=0&LoginForm%5BrememberMe%5D=1&LoginForm%5Busername%5D=" + Encoding.urlEncode(account.getUser()) + "&LoginForm%5Bpassword%5D=" + Encoding.urlEncode(account.getPass());
                if (logincaptcha != null) {
                    logincaptcha = "http://fboom.me" + logincaptcha;
                    final DownloadLink dummyLink = new DownloadLink(this, "Account", "fileboom.me", "http://fileboom.me", true);
                    final String c = getCaptchaCode(logincaptcha, dummyLink);
                    postData += "&LoginForm%5BverifyCode%5D=" + Encoding.urlEncode(c);
                }
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.postPage("http://fboom.me/login.html", postData);
                if (!br.containsHTML("\"url\":\"")) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername, ungültiges Passwort oder ungültiges Login Captcha!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password or login captcha!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                // Save cookies
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = br.getCookies(MAINPAGE);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        if (account.getUser() == null || !account.getUser().contains("@")) {
            account.setValid(false);
            ai.setStatus("Please use E-Mail as login/name!");
            return ai;
        }
        if (this.apiEnabled()) {
            pluginLoaded();
            final jd.plugins.hoster.Keep2ShareCc.kfpAPI api = ((jd.plugins.hoster.Keep2ShareCc) k2sPlugin).kfpAPI("fileboom.me");
            ai = api.fetchAccountInfo(account);
        } else {
            /* reset maxPrem workaround on every fetchaccount info */
            try {
                login(account, true);
            } catch (PluginException e) {
                account.setValid(false);
                throw e;
            }
            br.getPage("http://fboom.me/site/profile.html");
            ai.setUnlimitedTraffic();
            final String expire = br.getRegex("Premium expires:[\t\n\r ]+<b>([^<>\"]*?)</b>").getMatch(0);
            if (expire == null) {
                ai.setStatus("Free Account");
                account.setProperty("nopremium", true);
            } else {
                ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "yyyy.MM.dd", Locale.ENGLISH));
                ai.setStatus("Premium Account");
                account.setProperty("nopremium", false);
            }
            final String trafficleft = br.getRegex("Available traffic \\(today\\):[\t\n\r ]+<b><a href=\"/user/statistic\\.html\">([^<>\"]*?)</a>").getMatch(0);
            if (trafficleft != null) {
                ai.setTrafficLeft(SizeFormatter.getSize(trafficleft));
            }
        }
        // api can't set these!
        if (account.getBooleanProperty("free", false)) {
            setFreeAccount(account);
        } else {
            setPremiumAccount(account);
        }
        account.setValid(true);
        return ai;
    }

    private void setFreeAccount(Account account) {
        try {
            maxPrem.set(1);
            /* free accounts can still have captcha */
            account.setMaxSimultanDownloads(maxPrem.get());
            account.setConcurrentUsePossible(false);
        } catch (final Throwable e) {
            /* not available in old Stable 0.9.581 */
        }
    }

    private void setPremiumAccount(Account account) {
        try {
            maxPrem.set(20);
            account.setMaxSimultanDownloads(maxPrem.get());
            account.setConcurrentUsePossible(true);
        } catch (final Throwable e) {
            /* not available in old Stable 0.9.581 */
        }
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        setConstants(account);
        if (this.apiEnabled()) {
            pluginLoaded();
            final jd.plugins.hoster.Keep2ShareCc.kfpAPI api = ((jd.plugins.hoster.Keep2ShareCc) k2sPlugin).kfpAPI("fileboom.me");
            api.setChunks(chunks);
            api.setResumes(resumes);
            api.setDirectlinkproperty(directlinkproperty);
            api.setDl(dl);
            api.handleDownload(link, account);
        } else {
            requestFileInformation(link);
            login(account, false);
            br.setFollowRedirects(false);
            br.getPage(link.getDownloadURL());
            if (account.getBooleanProperty("nopremium", false)) {
                checkShowFreeDialog();
                doFree(link, account);
            } else {
                String dllink = br.getRedirectLocation();
                /* Maybe user has direct downloads disabled */
                if (dllink == null) {
                    dllink = getDllink();
                }
                dl = jd.plugins.BrowserAdapter.openDownload(br, link, Encoding.htmlDecode(dllink), resumes, chunks);
                if (dl.getConnection().getContentType().contains("html")) {
                    logger.warning("The final dllink seems not to be a file!");
                    br.followConnection();
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                dl.startDownload();
            }
        }
    }

    // private String getFID(final DownloadLink dl) {
    // return new Regex(dl.getDownloadURL(), "([a-z0-9]+)$").getMatch(0);
    // }

    private void checkShowFreeDialog() {
        SubConfiguration config = null;
        try {
            config = getPluginConfig();
            if (config.getBooleanProperty("premAdShown", Boolean.FALSE) == false) {
                if (config.getProperty("premAdShown2") == null) {
                    File checkFile = JDUtilities.getResourceFile("tmp/fboom");
                    if (!checkFile.exists()) {
                        checkFile.mkdirs();
                        showFreeDialog("fboom.me");
                    }
                } else {
                    config = null;
                }
            } else {
                config = null;
            }
        } catch (final Throwable e) {
        } finally {
            if (config != null) {
                config.setProperty("premAdShown", Boolean.TRUE);
                config.setProperty("premAdShown2", "shown");
                config.save();
            }
        }
    }

    protected void showFreeDialog(final String domain) {
        if (System.getProperty("org.jdownloader.revision") != null) { /* JD2 ONLY! */
            super.showFreeDialog(domain);
            return;
        }
        try {
            SwingUtilities.invokeAndWait(new Runnable() {

                @Override
                public void run() {
                    try {
                        String lng = System.getProperty("user.language");
                        String message = null;
                        String title = null;
                        String tab = "                        ";
                        if ("de".equalsIgnoreCase(lng)) {
                            title = domain + " Free Download";
                            message = "Du lädst im kostenlosen Modus von " + domain + ".\r\n";
                            message += "Wie bei allen anderen Hostern holt JDownloader auch hier das Beste für dich heraus!\r\n\r\n";
                            message += tab + "  Falls du allerdings mehrere Dateien\r\n" + "          - und das möglichst mit Fullspeed und ohne Unterbrechungen - \r\n" + "             laden willst, solltest du dir den Premium Modus anschauen.\r\n\r\nUnserer Erfahrung nach lohnt sich das - Aber entscheide am besten selbst. Jetzt ausprobieren?  ";
                        } else {
                            title = domain + " Free Download";
                            message = "You are using the " + domain + " Free Mode.\r\n";
                            message += "JDownloader always tries to get the best out of each hoster's free mode!\r\n\r\n";
                            message += tab + "   However, if you want to download multiple files\r\n" + tab + "- possibly at fullspeed and without any wait times - \r\n" + tab + "you really should have a look at the Premium Mode.\r\n\r\nIn our experience, Premium is well worth the money. Decide for yourself, though. Let's give it a try?   ";
                        }
                        if (CrossSystem.isOpenBrowserSupported()) {
                            int result = JOptionPane.showConfirmDialog(jd.gui.swing.jdgui.JDGui.getInstance().getMainFrame(), message, title, JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null);
                            if (JOptionPane.OK_OPTION == result) {
                                CrossSystem.openURL(new URL("http://update3.jdownloader.org/jdserv/BuyPremiumInterface/redirect?" + domain + "&freedialog"));
                            }
                        }
                    } catch (Throwable e) {
                    }
                }
            });
        } catch (Throwable e) {
        }
    }

    private String getDllink() throws IOException, PluginException {
        String dllink = br.getRegex("(\"|\\')(/file/url\\.html\\?file=[a-z0-9]+)(\"|\\')").getMatch(1);
        if (dllink != null) {
            dllink = "http://fboom.me" + dllink;
            br.getPage(dllink);
            dllink = br.getRegex("\"url\":\"(http[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                dllink = br.getRedirectLocation();
            }
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = dllink.replace("\\", "");
        }
        return dllink;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return maxPrem.get();
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    /* NO OVERRIDE!! We need to stay 0.9*compatible */
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        if (acc == null) {
            /* no account, yes we can expect captcha */
            return true;
        }
        if (Boolean.TRUE.equals(acc.getBooleanProperty("free"))) {
            /* free accounts also have captchas */
            return true;
        }
        if (Boolean.TRUE.equals(acc.getBooleanProperty("nopremium"))) {
            /* free accounts also have captchas */
            return true;
        }
        if (acc.getStringProperty("session_type") != null && !"premium".equalsIgnoreCase(acc.getStringProperty("session_type"))) {
            return true;
        }
        return false;
    }
}