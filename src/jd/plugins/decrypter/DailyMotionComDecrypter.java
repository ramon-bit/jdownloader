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

package jd.plugins.decrypter;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.config.SubConfiguration;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

import org.appwork.utils.formatter.TimeFormatter;

//Decrypts embedded videos from dailymotion
@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "dailymotion.com" }, urls = { "https?://(www\\.)?dailymotion\\.com/((embed/)?video/[a-z0-9\\-_]+|swf(/video)?/[a-zA-Z0-9]+|user/[A-Za-z0-9]+/\\d+|playlist/[A-Za-z0-9]+_[A-Za-z0-9\\-_]+/(\\d+)?|[A-Za-z0-9]+)" }, flags = { 0 })
public class DailyMotionComDecrypter extends PluginForDecrypt {

    public DailyMotionComDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    private String                          VIDEOSOURCE    = null;
    /**
     * @ 1hd1080URL or stream_h264_hd1080_url [1920x1080]
     * 
     * @ 2 hd720URL or stream_h264_hd_url [1280x720]
     * 
     * @ 3 hqURL or stream_h264_hq_url [848x480]
     * 
     * @ 4 sdURL or stream_h264_url [512x384]
     * 
     * @ 5 ldURL or video_url or stream_h264_ld_url [320x240]
     * 
     * @ 6 video_url or rtmp
     * 
     * @ 7 hds
     * 
     * @String[] = {"Direct download url", "filename, if available before quality selection"}
     */
    private LinkedHashMap<String, String[]> FOUNDQUALITIES = new LinkedHashMap<String, String[]>();
    private String                          FILENAME       = null;
    private String                          PARAMETER      = null;

    private static final String             ALLOW_LQ       = "ALLOW_LQ";
    private static final String             ALLOW_SD       = "ALLOW_SD";
    private static final String             ALLOW_HQ       = "ALLOW_HQ";
    private static final String             ALLOW_720      = "ALLOW_720";
    private static final String             ALLOW_1080     = "ALLOW_1080";
    private static final String             ALLOW_OTHERS   = "ALLOW_OTHERS";
    private static final String             ALLOW_HDS      = "ALLOW_HDS";

    private static final String             TYPE_USER      = "https?://(www\\.)?dailymotion\\.com/user/[A-Za-z0-9]+/\\d+";
    private static final String             TYPE_PLAYLIST  = "https?://(www\\.)?dailymotion\\.com/playlist/[A-Za-z0-9]+_[A-Za-z0-9\\-_]+/(\\d+)?";
    private static final String             TYPE_VIDEO     = "https?://(www\\.)?dailymotion\\.com/((embed/)?video/[a-z0-9\\-_]+|swf(/video)?/[a-zA-Z0-9]+)";
    private static final String             TYPE_INVALID   = "https?://(www\\.)?dailymotion\\.com/playlist";

    private ArrayList<DownloadLink>         decryptedLinks = new ArrayList<DownloadLink>();

    private boolean                         acc_in_use     = false;

    private static AtomicBoolean            pluginLoaded   = new AtomicBoolean(false);
    private static Object                   ctrlLock       = new Object();

    /**
     * JD2 CODE: DO NOIT USE OVERRIDE FÒR COMPATIBILITY REASONS!!!!!
     */
    public boolean isProxyRotationEnabledForLinkCrawler() {
        return false;
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        PARAMETER = param.toString().replace("www.", "").replace("embed/video/", "video/").replaceAll("\\.com/swf(/video)?/", ".com/video/").replace("https://", "http://");
        br.setFollowRedirects(true);

        if (PARAMETER.matches(TYPE_INVALID)) {
            logger.info("Invalid link: " + PARAMETER);
            return decryptedLinks;
        }
        synchronized (ctrlLock) {
            /* Login if account available */
            final PluginForHost dailymotionHosterplugin = JDUtilities.getPluginForHost("dailymotion.com");
            pluginLoaded.set(true);
            Account aa = AccountController.getInstance().getValidAccount(dailymotionHosterplugin);
            if (aa != null) {
                try {
                    ((jd.plugins.hoster.DailyMotionCom) dailymotionHosterplugin).login(aa, this.br);
                    acc_in_use = true;
                } catch (final PluginException e) {
                    logger.info("Account seems to be invalid -> Continuing without account!");
                }
            }
            /* Login end... */

            br.setCookie("http://www.dailymotion.com", "family_filter", "off");
            br.setCookie("http://www.dailymotion.com", "ff", "off");
            br.setCookie("http://www.dailymotion.com", "lang", "en_US");
            try {
                br.getPage(PARAMETER);
            } catch (final Exception e) {
                final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
                dl.setProperty("offline", true);
                decryptedLinks.add(dl);
                return decryptedLinks;
            }
            // 404
            if (br.containsHTML("(<title>Dailymotion \\– 404 Not Found</title>|url\\(/images/404_background\\.jpg)")) {
                final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
                dl.setProperty("offline", true);
                decryptedLinks.add(dl);
                return decryptedLinks;
            }
            // 403
            if (br.containsHTML("class=\"forbidden\">Access forbidden</h3>|>You don\\'t have permission to access the requested URL")) {
                final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
                dl.setProperty("offline", true);
                decryptedLinks.add(dl);
                return decryptedLinks;
            }
            if (PARAMETER.matches(TYPE_USER) || br.containsHTML("class=\"mrg-end-sm user-screenname-inner\"")) {
                decryptUser();
            } else if (PARAMETER.matches(TYPE_PLAYLIST)) {
                decryptPlaylist();
            } else {
                decryptSingleVideo(decryptedLinks);
            }
        }
        if (decryptedLinks == null) {
            logger.warning("Decrypter broken for link: " + PARAMETER);
            return null;
        }
        return decryptedLinks;
    }

    private void decryptUser() throws IOException {
        logger.info("Decrypting user: " + PARAMETER);
        String username = new Regex(PARAMETER, "dailymotion\\.com/user/([A-Za-z0-9]+)").getMatch(0);
        if (username == null) {
            username = new Regex(PARAMETER, "dailymotion\\.com/([A-Za-z0-9]+)").getMatch(0);
        }
        br.getPage("http://www.dailymotion.com/" + username);
        if (br.containsHTML("class=\"dmco_text nothing_to_see\"")) {
            final DownloadLink dl = createDownloadlink("http://dailymotiondecrypted.com/video/" + System.currentTimeMillis());
            dl.setFinalFileName(username);
            dl.setProperty("offline", true);
            decryptedLinks.add(dl);
            return;
        }
        String fpName = br.getRegex("class=\"mrg-end-sm user-screenname-inner\">([^<>\"]*?)</span>").getMatch(0);
        if (fpName == null) {
            fpName = username;
        }
        fpName = Encoding.htmlDecode(fpName.trim());
        final String videosNum = br.getRegex(Pattern.compile("<span class=\"font\\-xl mrg\\-end\\-xs\">(\\d+(,\\d+)?)</span>[\t\n\r ]+Videos?[\t\n\r ]+</div>", Pattern.CASE_INSENSITIVE)).getMatch(0);
        if (videosNum == null) {
            logger.warning("dailymotion.com: decrypter failed: " + PARAMETER);
            decryptedLinks = null;
            return;
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(fpName);
        final int videoCount = Integer.parseInt(videosNum.replace(",", ""));
        if (videoCount == 0) {
            /* User has 0 videos */
            final DownloadLink dl = createDownloadlink("http://dailymotiondecrypted.com/video/" + System.currentTimeMillis());
            dl.setFinalFileName(username);
            dl.setProperty("offline", true);
            decryptedLinks.add(dl);
            return;
        }
        String desiredPage = new Regex(PARAMETER, "/user/[A-Za-z0-9]+/(\\d+)$").getMatch(0);
        if (desiredPage == null) {
            desiredPage = "1";
        }
        boolean parsePageOnly = false;
        if (Integer.parseInt(desiredPage) != 1) {
            parsePageOnly = true;
        }
        int currentPage = Integer.parseInt(desiredPage);
        final BigDecimal bd = new BigDecimal((double) videoCount / 18);
        final int pagesNum = bd.setScale(0, BigDecimal.ROUND_UP).intValue();
        do {
            try {
                if (this.isAbort()) {
                    logger.info("dailymotion.com: Decrypt process aborted by user on page " + currentPage + " of " + pagesNum);
                    return;
                }
            } catch (final Throwable e) {
                // Not available in 0.9.581
            }
            logger.info("Decrypting page " + currentPage + " / " + pagesNum);
            br.getPage("http://www.dailymotion.com/user/" + username + "/" + currentPage);
            final String[] videos = br.getRegex("preview_link \"  href=\"(/video/[^<>\"/]+)\"").getColumn(0);
            if (videos == null || videos.length == 0) {
                logger.info("Found no videos on page " + currentPage + " -> Stopping decryption");
                break;
            }
            for (final String videolink : videos) {
                final DownloadLink fina = createDownloadlink("http://www.dailymotion.com" + videolink);
                fp.add(fina);
                try {
                    distribute(fina);
                } catch (final Throwable e) {
                    // Not available in 0.9.581
                }
                decryptedLinks.add(fina);
            }
            logger.info("dailymotion.com: Decrypted page " + currentPage + " of " + pagesNum);
            logger.info("dailymotion.com: Found " + videos.length + " links on current page");
            logger.info("dailymotion.com: Found " + decryptedLinks.size() + " of total " + videoCount + " links already...");
            currentPage++;
        } while (decryptedLinks.size() < videoCount && !parsePageOnly);
        if (decryptedLinks == null || decryptedLinks.size() == 0) {
            logger.warning("Dailymotion.com decrypter failed: " + PARAMETER);
            decryptedLinks = null;
            return;
        }
        fp.addLinks(decryptedLinks);
    }

    private void decryptPlaylist() throws IOException {
        logger.info("Decrypting playlist: " + PARAMETER);
        final Regex info = br.getRegex("class=\"name\">([^<>\"]*?)</a> \\| (\\d+(,\\d+)?) Videos?");
        String username = info.getMatch(0);
        if (username == null) {
            username = br.getRegex("<meta name=\"author\" content=\"([^<>\"]*?)\"").getMatch(0);
        }
        String fpName = br.getRegex("<div id=\"playlist_name\">([^<>\"]*?)</div>").getMatch(0);
        if (fpName == null) {
            fpName = br.getRegex("<div class=\"page\\-title mrg\\-btm\\-sm\">([^<>\"]*?)</div>").getMatch(0);
        }
        if (fpName == null) {
            fpName = br.getRegex("\"playlist_title\":\"([^<>\"]*?)\"").getMatch(0);
        }
        if (fpName == null) {
            fpName = new Regex(PARAMETER, "dailymotion.com/playlist/([A-Za-z0-9]+_[A-Za-z0-9\\-_]+)").getMatch(0);
        }
        fpName = Encoding.htmlDecode(fpName.trim());
        String videosNum = info.getMatch(1);
        final String videosnum_text = br.getRegex("class=\"link\\-on\\-hvr\"(.*?)<span>").getMatch(0);
        if (videosNum == null && videosnum_text != null) {
            videosNum = new Regex(videosnum_text, "(\\d+(,\\d+)?) Videos?").getMatch(0);
        }
        if (videosNum == null) {
            /* Empty playlist site */
            if (!br.containsHTML("\"watchlaterAdd\"")) {
                final DownloadLink dl = createDownloadlink("http://dailymotiondecrypted.com/video/playlistoffline_" + System.currentTimeMillis() + Encoding.urlEncode(fpName));
                dl.setFinalFileName(fpName);
                dl.setProperty("offline", true);
                decryptedLinks.add(dl);
                return;
            }
            logger.warning("dailymotion.com: decrypter failed: " + PARAMETER);
            decryptedLinks = null;
            return;
        }
        final FilePackage fp = FilePackage.getInstance();
        fpName = Encoding.htmlDecode(username).trim() + " - " + Encoding.htmlDecode(fpName).trim();
        fp.setName(fpName);
        final int videoCount = Integer.parseInt(videosNum.replace(",", ""));
        if (videoCount == 0) {
            /* User has 0 videos */
            final DownloadLink dl = createDownloadlink("http://dailymotiondecrypted.com/video/" + System.currentTimeMillis());
            dl.setFinalFileName(username);
            dl.setProperty("offline", true);
            decryptedLinks.add(dl);
            return;
        }
        String desiredPage = new Regex(PARAMETER, "playlist/[A-Za-z0-9]+_[A-Za-z0-9\\-_]+/(\\d+)").getMatch(0);
        if (desiredPage == null) {
            desiredPage = "1";
        }
        boolean parsePageOnly = false;
        if (Integer.parseInt(desiredPage) != 1) {
            parsePageOnly = true;
        }
        final BigDecimal bd = new BigDecimal((double) videoCount / 18);
        final int pagesNum = bd.setScale(0, BigDecimal.ROUND_UP).intValue();

        int currentPage = Integer.parseInt(desiredPage);
        final String base_link = "http://www.dailymotion.com/playlist/" + new Regex(PARAMETER, "/playlist/([^<>\"/]*?)/").getMatch(0) + "/";
        do {
            try {
                if (this.isAbort()) {
                    logger.info("dailymotion.com: Decrypt process aborted by user on page " + currentPage + " of " + pagesNum);
                    return;
                }
            } catch (final Throwable e) {
                // Not available in 0.9.581
            }
            logger.info("Decrypting page " + currentPage + " / " + pagesNum);
            br.getPage(base_link + currentPage);
            final String[] videos = br.getRegex("preview_link \"  href=\"(/video/[^<>\"/]+)\"").getColumn(0);
            if (videos == null || videos.length == 0) {
                logger.info("Found no videos on page " + currentPage + " -> Stopping decryption");
                break;
            }
            for (final String videolink : videos) {
                final DownloadLink fina = createDownloadlink("http://www.dailymotion.com" + videolink);
                fp.add(fina);
                try {
                    distribute(fina);
                } catch (final Throwable e) {
                    // Not available in 0.9.581
                }
                decryptedLinks.add(fina);
            }
            logger.info("dailymotion.com: Decrypted page " + currentPage + " of " + pagesNum);
            logger.info("dailymotion.com: Found " + videos.length + " links on current page");
            logger.info("dailymotion.com: Found " + decryptedLinks.size() + " of total " + videoCount + " links already...");
            currentPage++;
        } while (decryptedLinks.size() < videoCount && !parsePageOnly);
        if (decryptedLinks == null || decryptedLinks.size() == 0) {
            logger.warning("Dailymotion.com decrypter failed: " + PARAMETER);
            decryptedLinks = null;
            return;
        }
        fp.addLinks(decryptedLinks);
    }

    private String VIDEOID     = null;
    private String CHANNELNAME = null;
    private long   DATE        = 0;

    protected void decryptSingleVideo(ArrayList<DownloadLink> decryptedLinks) throws IOException, ParseException {
        logger.info("Decrypting single video: " + PARAMETER);
        // We can't download livestreams
        if (br.containsHTML("DMSTREAMMODE=live")) {
            final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
            dl.setProperty("offline", true);
            decryptedLinks.add(dl);
            return;
        }
        /** Decrypt start */
        /** Decrypt external links START */
        String externID = br.getRegex("player\\.hulu\\.com/express/(\\d+)").getMatch(0);
        if (externID != null) {
            decryptedLinks.add(createDownloadlink("http://www.hulu.com/watch/" + externID));
            return;
        }
        externID = br.getRegex("name=\"movie\" value=\"(http://(www\\.)?embed\\.5min\\.com/\\d+)").getMatch(0);
        if (externID != null) {
            decryptedLinks.add(createDownloadlink(externID));
            return;
        }
        externID = br.getRegex("\"(http://videoplayer\\.vevo\\.com/embed/embedded\\?videoId=[A-Za-z0-9]+)").getMatch(0);
        if (externID != null) {
            decryptedLinks.add(createDownloadlink(externID));
            return;
        }
        /** Decrypt external links END */
        /** Find videolinks START */
        VIDEOID = new Regex(PARAMETER, "dailymotion\\.com/video/([a-z0-9]+)").getMatch(0);
        CHANNELNAME = br.getRegex("\"owner\":\"([^<>\"]*?)\"").getMatch(0);
        String strdate = br.getRegex("property=\"video:release_date\" content=\"([^<>\"]*?)\"").getMatch(0);
        VIDEOSOURCE = getVideosource(this.br);
        FILENAME = br.getRegex("<meta itemprop=\"name\" content=\"([^<>\"]*?)\"").getMatch(0);
        if (FILENAME == null) {
            FILENAME = br.getRegex("<meta property=\"og:title\" content=\"([^<>\"]*?)\"").getMatch(0);
        }
        if (VIDEOSOURCE == null || FILENAME == null || VIDEOID == null || CHANNELNAME == null || strdate == null) {
            logger.warning("Dailymotion.com decrypter failed: " + PARAMETER);
            final DownloadLink dl = createDownloadlink("http://dailymotiondecrypted.com/video/" + System.currentTimeMillis());
            dl.setFinalFileName(new Regex(PARAMETER, "dailymotion\\.com/(.+)").getMatch(0));
            dl.setProperty("offline", true);
            dl.setProperty("pluginmaybebroken", true);
            decryptedLinks.add(dl);
            return;
        }

        /* Fix date */
        strdate = strdate.replace("T", "").replace("+", "GMT");
        DATE = TimeFormatter.getMilliSeconds(strdate, "yyyy-MM-ddHH:mm:ssz", Locale.ENGLISH);

        FILENAME = Encoding.htmlDecode(FILENAME.trim()).replace(":", " - ").replaceAll("/|<|>", "");
        if (new Regex(VIDEOSOURCE, "(Dein Land nicht abrufbar|this content is not available for your country|This video has not been made available in your country by the owner|\"Video not available due to geo\\-restriction)").matches()) {
            final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
            dl.setFinalFileName("Geo restricted video - " + FILENAME + ".mp4");
            dl.setProperty("countryblock", true);
            dl.setAvailable(true);
            decryptedLinks.add(dl);
            return;
        } else if (new Regex(VIDEOSOURCE, "\"title\":\"Video geo\\-restricted by the owner").matches()) {
            final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
            dl.setFinalFileName("Geo-Restricted by owner - " + FILENAME + ".mp4");
            dl.setProperty("offline", true);
            dl.setAvailable(false);
            decryptedLinks.add(dl);
        } else if (new Regex(VIDEOSOURCE, "(his content as suitable for mature audiences only|You must be logged in, over 18 years old, and set your family filter OFF, in order to watch it)").matches() && !acc_in_use) {
            final DownloadLink dl = createDownloadlink("+18 video - " + PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
            dl.setFinalFileName(FILENAME + ".mp4");
            dl.setProperty("registeredonly", true);
            dl.setAvailable(true);
            decryptedLinks.add(dl);
            return;
        } else if (new Regex(VIDEOSOURCE, "\"message\":\"Publication of this video is in progress").matches()) {
            final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
            dl.setFinalFileName("Publication of this video is in progress - " + FILENAME + ".mp4");
            dl.setProperty("offline", true);
            dl.setAvailable(false);
            decryptedLinks.add(dl);
            return;
        } else if (new Regex(VIDEOSOURCE, "\"encodingMessage\":\"Encoding in progress\\.\\.\\.\"").matches()) {
            final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
            dl.setFinalFileName("Encoding in progress - " + FILENAME + ".mp4");
            dl.setProperty("offline", true);
            dl.setAvailable(false);
            decryptedLinks.add(dl);
            return;
        } else if (new Regex(VIDEOSOURCE, "\"title\":\"Channel offline\\.\"").matches()) {
            final DownloadLink dl = createDownloadlink(PARAMETER.replace("dailymotion.com/", "dailymotiondecrypted.com/"));
            dl.setFinalFileName("Channel offline - " + FILENAME + ".mp4");
            dl.setProperty("offline", true);
            dl.setAvailable(false);
            decryptedLinks.add(dl);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(FILENAME);

        /** Decrypt subtitles if available */
        String[] subtitles = new Regex(VIDEOSOURCE, "\"(http://static\\d+\\.dmcdn\\.net/static/video/\\d+/\\d+/\\d+:subtitle_[a-z]{1,4}\\.srt\\?\\d+)\"").getColumn(0);
        if (subtitles != null && subtitles.length != 0) {
            final FilePackage fpSub = FilePackage.getInstance();
            fpSub.setName(FILENAME + "_Subtitles");
            for (final String subtitle : subtitles) {
                final DownloadLink dl = createDownloadlink("http://dailymotiondecrypted.com/video/" + System.currentTimeMillis() + new Random().nextInt(10000));
                final String language = new Regex(subtitle, ".*?\\d+:subtitle_(.{1,4}).srt.*?").getMatch(0);
                String qualityname = "subtitle";
                if (language != null) {
                    qualityname += "_" + language;
                }
                dl.setProperty("directlink", subtitle);
                dl.setProperty("type_subtitle", true);
                dl.setProperty("qualityname", qualityname);
                dl.setProperty("mainlink", PARAMETER);
                dl.setProperty("plain_videoname", FILENAME);
                dl.setProperty("plain_ext", ".srt");
                dl.setProperty("plain_videoid", VIDEOID);
                dl.setProperty("plain_channel", CHANNELNAME);
                dl.setProperty("plain_date", Long.toString(DATE));
                dl.setProperty("LINKDUPEID", "dailymotioncom" + VIDEOID + "_" + qualityname);
                final String formattedFilename = jd.plugins.hoster.DailyMotionCom.getFormattedFilename(dl);
                dl.setName(formattedFilename);
                fpSub.add(dl);
                decryptedLinks.add(dl);
            }
        }

        FOUNDQUALITIES = findVideoQualities(this.br, PARAMETER, VIDEOSOURCE);
        if (FOUNDQUALITIES.isEmpty() && decryptedLinks.size() == 0) {
            logger.warning("Found no quality for link: " + PARAMETER);
            decryptedLinks = null;
            return;
        }
        /** Find videolinks END */
        /** Pick qualities, selected by the user START */
        final ArrayList<String> selectedQualities = new ArrayList<String>();
        final SubConfiguration cfg = SubConfiguration.getConfig("dailymotion.com");
        if (cfg.getBooleanProperty("ALLOW_BEST", false)) {
            ArrayList<String> list = new ArrayList<String>(FOUNDQUALITIES.keySet());
            final String highestAvailableQualityValue = list.get(list.size() - 1);
            selectedQualities.add(highestAvailableQualityValue);
        } else {
            boolean qld = cfg.getBooleanProperty(ALLOW_LQ, false);
            boolean qsd = cfg.getBooleanProperty(ALLOW_SD, false);
            boolean qhq = cfg.getBooleanProperty(ALLOW_HQ, false);
            boolean q720 = cfg.getBooleanProperty(ALLOW_720, false);
            boolean q1080 = cfg.getBooleanProperty(ALLOW_1080, false);
            boolean others = cfg.getBooleanProperty(ALLOW_OTHERS, false);
            boolean hds = cfg.getBooleanProperty(ALLOW_HDS, false);
            /** User selected nothing -> Decrypt everything */
            if (qld == false && qsd == false && qhq == false && q720 == false && q1080 == false && others == false && hds == false) {
                qld = true;
                qsd = true;
                qhq = true;
                q720 = true;
                q1080 = true;
                others = true;
                hds = true;
            }
            if (qld) {
                selectedQualities.add("5");
            }
            if (qsd) {
                selectedQualities.add("4");
            }
            if (qhq) {
                selectedQualities.add("3");
            }
            if (q720) {
                selectedQualities.add("2");
            }
            if (q1080) {
                selectedQualities.add("1");
            }
            if (others) {
                selectedQualities.add("6");
            }
            if (hds) {
                selectedQualities.add("7");
            }
        }
        for (final String selectedQualityValue : selectedQualities) {
            final DownloadLink dl = getVideoDownloadlink(this.br, FOUNDQUALITIES, selectedQualityValue);
            if (dl != null) {
                fp.add(dl);
                decryptedLinks.add(dl);
            }
        }
        /** Pick qualities, selected by the user END */
        if (decryptedLinks.size() == 0) {
            logger.info("None of the selected qualities were found, decrypting done...");
            return;
        }
    }

    public static LinkedHashMap<String, String[]> findVideoQualities(final Browser br, final String parameter, String videosource) throws IOException {
        LinkedHashMap<String, String[]> QUALITIES = new LinkedHashMap<String, String[]>();
        final String[][] qualities = { { "hd1080URL", "1" }, { "hd720URL", "2" }, { "hqURL", "3" }, { "sdURL", "4" }, { "ldURL", "5" }, { "video_url", "5" } };
        for (final String quality[] : qualities) {
            final String qualityName = quality[0];
            final String qualityNumber = quality[1];
            final String currentQualityUrl = getQuality(qualityName, videosource);
            if (currentQualityUrl != null) {
                final String[] dlinfo = new String[4];
                dlinfo[0] = currentQualityUrl;
                dlinfo[1] = null;
                dlinfo[2] = qualityName;
                dlinfo[3] = qualityNumber;
                QUALITIES.put(qualityNumber, dlinfo);
            }
        }
        // List empty or only 1 link found -> Check for (more) links
        if (QUALITIES.isEmpty() || QUALITIES.size() == 1) {
            final String manifestURL = new Regex(videosource, "\"autoURL\":\"(http://[^<>\"]*?)\"").getMatch(0);
            if (manifestURL != null) {
                /** HDS */
                final String[] dlinfo = new String[4];
                dlinfo[0] = manifestURL;
                dlinfo[1] = "hds";
                dlinfo[2] = "autoURL";
                dlinfo[3] = "7";
                QUALITIES.put("7", dlinfo);
            }

            // Try to avoid HDS
            br.getPage("http://www.dailymotion.com/embed/video/" + new Regex(parameter, "([A-Za-z0-9\\-_]+)$").getMatch(0));
            videosource = br.getRegex("var info = \\{(.*?)\\},").getMatch(0);
            if (videosource != null) {
                videosource = Encoding.htmlDecode(videosource).replace("\\", "");
                final String[][] embedQualities = { { "stream_h264_ld_url", "5" }, { "stream_h264_url", "4" }, { "stream_h264_hq_url", "3" }, { "stream_h264_hd_url", "2" }, { "stream_h264_hd1080_url", "1" } };
                for (final String quality[] : embedQualities) {
                    final String qualityName = quality[0];
                    final String qualityNumber = quality[1];
                    final String currentQualityUrl = getQuality(qualityName, videosource);
                    if (currentQualityUrl != null) {
                        final String[] dlinfo = new String[4];
                        dlinfo[0] = currentQualityUrl;
                        dlinfo[1] = null;
                        dlinfo[2] = qualityName;
                        dlinfo[3] = qualityNumber;
                        QUALITIES.put(qualityNumber, dlinfo);
                    }
                }
            }
            // if (FOUNDQUALITIES.isEmpty()) {
            // String[] values =
            // br.getRegex("new SWFObject\\(\"(http://player\\.grabnetworks\\.com/swf/GrabOSMFPlayer\\.swf)\\?id=\\d+\\&content=v([0-9a-f]+)\"").getRow(0);
            // if (values == null || values.length != 2) {
            // /** RTMP */
            // final DownloadLink dl = createDownloadlink("http://dailymotiondecrypted.com/video/" + System.currentTimeMillis() + new
            // Random(10000));
            // dl.setProperty("isrtmp", true);
            // dl.setProperty("mainlink", PARAMETER);
            // dl.setFinalFileName(FILENAME + "_RTMP.mp4");
            // fp.add(dl);
            // decryptedLinks.add(dl);
            // return decryptedLinks;
            // }
            // }
        }
        return QUALITIES;
    }

    /* Sync the following functions in hoster- and decrypterplugin */
    public static String getVideosource(final Browser br) {
        String videosource = br.getRegex("\"sequence\":\"([^<>\"]*?)\"").getMatch(0);
        if (videosource == null) {
            videosource = br.getRegex("%2Fsequence%2F(.*?)</object>").getMatch(0);
        }
        if (videosource == null) {
            videosource = br.getRegex("name=\"flashvars\" value=\"(.*?)\"/></object>").getMatch(0);
        }
        if (videosource != null) {
            videosource = Encoding.htmlDecode(videosource).replace("\\", "");
        }
        return videosource;
    }

    private DownloadLink getVideoDownloadlink(final Browser br, final LinkedHashMap<String, String[]> foundqualities, final String qualityValue) throws ParseException {
        String directlinkinfo[] = foundqualities.get(qualityValue);
        if (directlinkinfo != null) {
            final String directlink = Encoding.htmlDecode(directlinkinfo[0]);
            final DownloadLink dl = createDownloadlink("http://dailymotiondecrypted.com/video/" + System.currentTimeMillis() + new Random().nextInt(10000));
            String qualityName = directlinkinfo[1];
            if (qualityName == null) {
                qualityName = new Regex(directlink, "cdn/([^<>\"]*?)/video").getMatch(0);
            }
            final String originalQualityName = directlinkinfo[2];
            final String qualityNumber = directlinkinfo[3];
            dl.setProperty("directlink", directlink);
            dl.setProperty("qualityvalue", qualityValue);
            dl.setProperty("qualityname", qualityName);
            dl.setProperty("originalqualityname", originalQualityName);
            dl.setProperty("qualitynumber", qualityNumber);
            dl.setProperty("mainlink", PARAMETER);
            dl.setProperty("plain_videoname", FILENAME);
            dl.setProperty("plain_ext", ".mp4");
            dl.setProperty("plain_videoid", VIDEOID);
            dl.setProperty("plain_channel", CHANNELNAME);
            dl.setProperty("plain_date", Long.toString(DATE));
            dl.setProperty("LINKDUPEID", "dailymotioncom" + VIDEOID + "_" + qualityName);
            final String formattedFilename = jd.plugins.hoster.DailyMotionCom.getFormattedFilename(dl);
            dl.setName(formattedFilename);
            return dl;
        } else {
            return null;
        }
    }

    private static String getQuality(final String quality, final String videosource) {
        return new Regex(videosource, "\"" + quality + "\":\"(http[^<>\"\\']+)\"").getMatch(0);
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}