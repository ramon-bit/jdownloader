package jd.plugins.download.raf;

//jDownloader - Downloadmanager
//Copyright (C) 2008  JD-Team support@jdownloader.org
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

import java.awt.Color;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import jd.controlling.downloadcontroller.DiskSpaceReservation;
import jd.controlling.downloadcontroller.DownloadController;
import jd.controlling.downloadcontroller.ExceptionRunnable;
import jd.controlling.downloadcontroller.FileIsLockedException;
import jd.controlling.downloadcontroller.ManagedThrottledConnectionHandler;
import jd.http.Browser;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.BrowserAdapter;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.download.DownloadInterface;
import jd.plugins.download.Downloadable;
import jd.plugins.download.HashInfo;
import jd.plugins.download.HashResult;

import org.appwork.exceptions.WTFException;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.Exceptions;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.logging2.LogSource;
import org.jdownloader.plugins.DownloadPluginProgress;
import org.jdownloader.plugins.SkipReason;
import org.jdownloader.plugins.SkipReasonException;
import org.jdownloader.settings.GeneralSettings;
import org.jdownloader.translate._JDT;
import org.jdownloader.updatev2.InternetConnectionSettings;

public class OldRAFDownload extends DownloadInterface {

    public static final Object                  HASHCHECKLOCK            = new Object();
    private RandomAccessFile                    outputPartFileRaf;
    private File                                outputCompleteFile;
    private File                                outputFinalCompleteFile;
    private File                                outputPartFile;
    private AtomicBoolean                       connected                = new AtomicBoolean(false);
    private CopyOnWriteArrayList<RAFChunk>      chunks                   = new CopyOnWriteArrayList<RAFChunk>();

    protected long                              totalLinkBytesLoaded     = 0;
    protected AtomicLong                        totalLinkBytesLoadedLive = new AtomicLong(0);

    private int                                 readTimeout              = 100000;
    private int                                 requestTimeout           = 100000;

    private AtomicBoolean                       terminated               = new AtomicBoolean(false);
    private AtomicBoolean                       abort                    = new AtomicBoolean(false);

    protected int                               chunkNum                 = 1;
    private boolean                             resume                   = false;

    protected boolean                           dlAlreadyFinished        = false;
    protected Browser                           browser;
    protected URLConnectionAdapter              connection;

    protected Downloadable                      downloadable;

    protected PluginException                   caughtPluginException    = null;
    public Logger                               logger;

    public static final String                  PROPERTY_DOFILESIZECHECK = "DOFILESIZECHECK";
    protected Request                           request                  = null;
    protected ManagedThrottledConnectionHandler connectionHandler        = null;
    private long                                startTimeStamp           = -1;
    private boolean                             resumedDownload;

    /**
     * Gibt die Anzahl der Chunks an die dieser Download verwenden soll. Chu8nks koennen nur vor dem Downloadstart gesetzt werden!
     */
    public void setChunkNum(int num) {
        if (num <= 0) {
            logger.severe("Chunks value must be >=1");
            return;
        }
        chunkNum = num;
    }

    public boolean isResumable() {
        return downloadable.isResumable();
    }

    @Override
    public ManagedThrottledConnectionHandler getManagedConnetionHandler() {
        return connectionHandler;
    }

    /**
     * Ist resume aktiv?
     */
    public boolean isRangeRequestSupported() {
        return resume;
    }

    public OldRAFDownload(Downloadable downloadLink, Request request) throws IOException, PluginException {
        init(downloadLink, request);
    }

    protected void init(Downloadable downloadLink, Request request) {
        connectionHandler = new ManagedThrottledConnectionHandler();
        this.downloadable = downloadLink;
        logger = downloadLink.getLogger();
        browser = downloadLink.getContextBrowser();
        InternetConnectionSettings config = JsonConfig.create(InternetConnectionSettings.PATH, InternetConnectionSettings.class);
        requestTimeout = config.getHttpConnectTimeout();
        readTimeout = config.getHttpReadTimeout();
        this.request = request;
        /* setDownloadInstance after all variables are set! */
        downloadLink.setDownloadInterface(this);
    }

    /**
     * File soll resumed werden
     */
    public void setResume(boolean value) {
        resume = value;
        if (value && !checkResumabled()) {
            logger.warning("Resumepoint not valid");
        }
        downloadable.setResumeable(value);
    }

    /**
     * @return the startTimeStamp
     */
    public long getStartTimeStamp() {
        return startTimeStamp;
    }

    @Override
    public URLConnectionAdapter connect(Browser br) throws Exception {
        /* reset timeouts here, because it can be they got not set yet */
        setReadTimeout(br.getReadTimeout());
        setRequestTimeout(br.getConnectTimeout());
        request.setConnectTimeout(getRequestTimeout());
        request.setReadTimeout(getReadTimeout());
        br.setRequest(request);
        URLConnectionAdapter ret = connect();
        return ret;
    }

    public URLConnectionAdapter connect() throws Exception {
        if (connected.getAndSet(true) == true) {
            throw new IllegalStateException("Already connected");
        }
        logger.finer("Connect...");
        if (request == null) {
            throw new IllegalStateException("Wrong Mode. Instance is in direct Connection mode");
        }
        boolean resumed = false;
        if (this.isRangeRequestSupported() && this.checkResumabled()) {
            /* we can continue to resume the download */
            logger.finer(".....connectResumable");
            resumed = connectResumable();
        } else {
            long verifiedFileSize = downloadable.getVerifiedFileSize();
            if (verifiedFileSize > 0 && getChunkNum() > 1) {
                /* check if we have to adapt the number of chunks */
                int tmp = Math.min(Math.max(1, (int) (verifiedFileSize / RAFChunk.MIN_CHUNKSIZE)), getChunkNum());
                if (tmp != getChunkNum()) {
                    logger.finer("Corrected Chunknum: " + getChunkNum() + " -->" + tmp);
                    setChunkNum(tmp);
                }
            }
            if (this.isRangeRequestSupported()) {
                /* range requests are supported! */
                logger.finer(".....connectFirstRange");
                connectFirstRange();
            } else {
                logger.finer(".....connectRangeless");
                /* our connection happens rangeless */
                request.getHeaders().remove("Range");
                /* Workaround for rayfile.com */
                if (this.downloadable.isServerComaptibleForByteRangeRequest()) {
                    if ("rayfile.com".contains(this.downloadable.getHost())) {
                        request.getHeaders().put("Range", "bytes=" + (0) + "-");
                    }
                }
                browser.openRequestConnection(request, false);
            }
        }
        if (downloadable.isDebug()) {
            logger.finest("\r\n" + request.printHeaders());
        }
        connection = request.getHttpConnection();
        if (request.getLocation() != null) {
            throw new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, BrowserAdapter.ERROR_REDIRECTED);
        }
        if (downloadable.getVerifiedFileSize() < 0) {
            /* only set DownloadSize if we do not have a verified FileSize yet */
            String contentType = connection.getContentType();
            boolean trustFileSize = contentType == null || (!contentType.contains("html") && !contentType.contains("text"));
            logger.info("Trust FileSize: " + trustFileSize + " " + contentType);
            if (connection.getRange() != null && trustFileSize) {
                /* we have a range response, let's use it */
                if (connection.getRange()[2] > 0) {
                    this.downloadable.setDownloadTotalBytes(connection.getRange()[2]);
                }
            } else if (resumed == false && connection.getLongContentLength() > 0 && connection.isOK() && trustFileSize) {
                this.downloadable.setDownloadTotalBytes(connection.getLongContentLength());
            }
        }
        if (connection.getResponseCode() == 416 && resumed == true && downloadable.getChunksProgress() != null && downloadable.getChunksProgress().length == 1 && downloadable.getVerifiedFileSize() == downloadable.getChunksProgress()[0] + 1) {
            logger.info("Faking Content-Disposition for already finished downloads");
            /* we requested a finished loaded file, got 416 and content-range with * and one chunk only */
            /* we fake a content disposition connection so plugins work normal */
            if (connection.isContentDisposition() == false) {
                List<String> list = new ArrayList<String>();
                list.add("fakeContent");
                connection.getHeaderFields().put("Content-Disposition", list);
            }
            List<String> list = new ArrayList<String>();
            list.add("application/octet-stream");
            connection.getHeaderFields().put("Content-Type", list);
            dlAlreadyFinished = true;
        }
        return connection;
    }

    /**
     * Gibt den aktuellen readtimeout zurueck
     */
    public int getReadTimeout() {
        return Math.max(10000, readTimeout);
    }

    /**
     * Gibt den requesttimeout zurueck
     */
    public int getRequestTimeout() {
        return Math.max(10000, requestTimeout);
    }

    /**
     * Validiert das Chunk Progress array
     */
    protected boolean checkResumabled() {
        long[] chunksP = downloadable.getChunksProgress();
        if (chunksP == null || chunksP.length == 0) {
            return false;
        }
        long fileSize = getFileSize();
        int chunks = chunksP.length;
        long part = fileSize / chunks;
        long dif;
        long last = -1;

        for (int i = 0; i < chunks; i++) {
            dif = chunksP[i] - i * part;
            if (dif < 0) {
                return false;
            }
            if (chunksP[i] <= last) {
                return false;
            }
            if (chunksP[i] >= (i + 1) * part) {
                logger.info("Correct Chunk " + i + " : " + chunksP[i] + " to " + (((i + 1) * part) - 1));
                chunksP[i] = ((i + 1) * part) - 1;
            }

            last = chunksP[i];

        }
        downloadable.setChunksProgress(chunksP);
        if (chunks > 0) {
            if (chunks <= this.getChunkNum()) {
                /* downloadchunks are less or equal to allowed chunks */
                setChunkNum(chunks);
            } else {
                /*
                 * downloadchunks are more than allowed chunks, need to repartition the download
                 */
                logger.info("Download has " + chunks + " Chunks but only " + getChunkNum() + " allowed! Change to 1!");
                setChunkNum(1);
                downloadable.setChunksProgress(new long[] { chunksP[0] });
            }
            return true;
        }
        return false;
    }

    protected void connectFirstRange() throws IOException {
        long fileSize = getFileSize();
        long part = fileSize / this.getChunkNum();
        boolean verifiedSize = downloadable.getVerifiedFileSize() > 0;
        boolean openRangeRequested = false;
        boolean rangeRequested = false;
        if (true || verifiedSize == false || this.getChunkNum() == 1) {
            /* we only request a single range */
            openRangeRequested = true;
            /* Workaround for server responses != 206 */
            if (this.downloadable.isServerComaptibleForByteRangeRequest()) {
                rangeRequested = true;
                request.getHeaders().put("Range", "bytes=" + (0) + "-");
            }
        } else {
            /* we request multiple ranges */
            openRangeRequested = false;
            rangeRequested = true;
            request.getHeaders().put("Range", "bytes=" + (0) + "-" + (part - 1));
        }
        browser.openRequestConnection(request, false);
        if (request.getHttpConnection().getResponseCode() == 416) {
            logger.warning("HTTP/1.1 416 Requested Range Not Satisfiable");
            if (downloadable.isDebug()) {
                logger.finest("\r\n" + request.printHeaders());
            }
            throw new IllegalStateException("HTTP/1.1 416 Requested Range Not Satisfiable");
        } else if (request.getHttpConnection().getRange() == null) {
            if (openRangeRequested && rangeRequested == false) {
                logger.warning("FirstRange was openRange without any RangeRequest!");
            } else {
                logger.warning("No Chunkload");
                setChunkNum(1);
            }
        } else {
            long[] range = request.getHttpConnection().getRange();
            if (range[0] != 0) {
                /* first range MUST start at zero */
                throw new IllegalStateException("Range Error. Requested " + request.getHeaders().get("Range") + ". Got range: " + request.getHttpConnection().getHeaderField("Content-Range"));
            } else if (verifiedSize && range[1] < (part - 1)) {
                /* response range != requested range */
                throw new IllegalStateException("Range Error. Requested " + request.getHeaders().get("Range") + " Got range: " + request.getHttpConnection().getHeaderField("Content-Range"));
            } else if (!openRangeRequested && range[1] == range[2] - 1 && getChunkNum() > 1) {
                logger.warning(" Chunkload Protection.. Requested " + request.getHeaders().get("Range") + " Got range: " + request.getHttpConnection().getHeaderField("Content-Range"));
                setChunkNum(1);
            } else if (verifiedSize && range[1] > (part - 1)) {
                /* response range is bigger than requested range */
                if (verifiedSize && range[1] == part) {
                    logger.severe("Workaround for buggy http server: rangeEND=contentEND, it must be rangeEND-1=contentEND as 0 is first byte!");
                    return;
                }
                if (request.getHttpConnection().getResponseCode() == 200 && rangeRequested == false && verifiedSize && fileSize == range[2]) {
                    logger.severe("Workaround for buggy http server: no range requested, but got content-range response with 200 header");
                    return;
                }
                throw new IllegalStateException("Range Error. Requested " + request.getHeaders().get("Range") + " Got range: " + request.getHttpConnection().getHeaderField("Content-Range"));
            }
        }
    }

    /**
     * Setzt im Downloadlink und PLugin die entsprechende Fehlerids
     */
    public boolean handleErrors() throws PluginException {
        if (externalDownloadStop()) {
            return false;
        }
        if (getFileSize() > 0 && totalLinkBytesLoaded != getFileSize()) {
            if (totalLinkBytesLoaded > getFileSize()) {
                /*
                 * workaround for old bug deep in this downloadsystem. more data got loaded (maybe just counting bug) than filesize. but in
                 * most cases the file is okay! WONTFIX because new downloadsystem is on its way
                 */
                logger.severe("Filesize: " + getFileSize() + " Loaded: " + totalLinkBytesLoaded);
                if (caughtPluginException == null) {
                    downloadable.setLinkStatus(LinkStatus.FINISHED);

                }
                return true;
            }
            logger.severe("Filesize: " + getFileSize() + " Loaded: " + totalLinkBytesLoaded);
            logger.severe("DOWNLOAD INCOMPLETE DUE TO FILESIZECHECK");
            if (caughtPluginException != null) {
                throw caughtPluginException;
            }
            throw new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, _JDT._.download_error_message_incomplete());
        }
        if (caughtPluginException == null) {
            downloadable.setLinkStatus(LinkStatus.FINISHED);
            return true;
        } else {
            throw caughtPluginException;
        }
    }

    /**
     * ueber error() kann ein fehler gemeldet werden. DIe Methode entscheided dann ob dieser fehler zu einem Abbruch fuehren muss
     */
    protected void error(PluginException pluginException) {
        synchronized (this) {
            /* if we recieved external stop, then we dont have to handle errors */
            if (externalDownloadStop()) {
                return;
            }
            LogSource.exception(logger, pluginException);
            if (caughtPluginException == null) {
                caughtPluginException = pluginException;
            }
        }
        terminate(false);
    }

    /**
     * Wartet bis alle Chunks fertig sind, aktuelisiert den downloadlink regelmaesig und fordert beim Controller eine aktualisierung des
     * links an
     */
    protected void onChunkFinished(RAFChunk chunk) {
        synchronized (chunks) {
            chunks.notifyAll();
        }
    }

    protected synchronized boolean writeBytes(RAFChunk chunk) {
        return writeChunkBytes(chunk);
    }

    /**
     * Startet den Download. Nach dem Aufruf dieser Funktion koennen keine Downlaodparameter mehr gesetzt werden bzw bleiben wirkungslos.
     * 
     * @return
     * @throws Exception
     */
    public boolean startDownload() throws Exception {
        try {
            try {
                downloadable.validateLastChallengeResponse();
            } catch (final Throwable e) {
                LogSource.exception(logger, e);
            }
            logger.finer("Start Download");
            if (this.dlAlreadyFinished == true) {

                downloadable.setAvailable(AvailableStatus.TRUE);
                logger.finer("DownloadAlreadyFinished workaround");
                downloadable.setLinkStatus(LinkStatus.FINISHED);
                return true;
            }
            if (connected.get() == false) {
                connect();
            }
            if (connection != null && connection.getHeaderField("Content-Encoding") != null && connection.getHeaderField("Content-Encoding").equalsIgnoreCase("gzip")) {
                /* GZIP Encoding kann weder chunk noch resume */
                /* hier dann auch den final filesize check prüfen */
                setResume(false);
                setChunkNum(1);
            }
            // Erst hier Dateinamen holen, somit umgeht man das Problem das bei
            // mehrfachAufruf von connect entstehen kann
            if (this.downloadable.getFinalFileName() == null && ((connection != null && connection.isContentDisposition()) || this.allowFilenameFromURL)) {
                String name = Plugin.getFileNameFromHeader(connection);
                if (this.fixWrongContentDispositionHeader) {
                    this.downloadable.setFinalFileName(Encoding.htmlDecode(name));
                } else {
                    this.downloadable.setFinalFileName(name);
                }
            }
            if (connection == null || !connection.isOK()) {
                if (connection != null) {
                    logger.finest(connection.toString());
                }
                try {
                    connection.disconnect();
                } catch (final Throwable e) {
                }
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 10 * 60 * 1000l);
            }
            if (connection.getHeaderField("Location") != null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }

            if (downloadable.getVerifiedFileSize() < 0) {
                /* we don't have a verified filesize yet, let's check if we have it now! */
                if (connection.getRange() != null) {
                    if (connection.getRange()[2] >= 0) {
                        downloadable.setVerifiedFileSize(connection.getRange()[2]);
                    }
                } else if (connection.getRequestProperty("Range") == null && connection.getLongContentLength() >= 0 && connection.isOK()) {
                    downloadable.setVerifiedFileSize(connection.getLongContentLength());
                }
            }

            try {
                DownloadPluginProgress downloadPluginProgress = null;
                downloadable.setConnectionHandler(this.getManagedConnetionHandler());
                final DiskSpaceReservation reservation = downloadable.createDiskSpaceReservation();
                try {
                    if (!downloadable.checkIfWeCanWrite(new ExceptionRunnable() {

                        @Override
                        public void run() throws Exception {
                            downloadable.checkAndReserve(reservation);
                            createOutputChannel();
                            try {
                                downloadable.lockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
                            } catch (FileIsLockedException e) {
                                downloadable.unlockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
                                throw new PluginException(LinkStatus.ERROR_ALREADYEXISTS);
                            }
                        }
                    }, null)) {
                        throw new SkipReasonException(SkipReason.INVALID_DESTINATION);
                    }
                    startTimeStamp = System.currentTimeMillis();
                    downloadPluginProgress = new DownloadPluginProgress(downloadable, this, Color.GREEN.darker());
                    downloadable.addPluginProgress(downloadPluginProgress);
                    setupChunks();
                    /* download in progress so file should be online ;) */
                    downloadable.setAvailable(AvailableStatus.TRUE);
                    waitForChunks();
                } finally {
                    try {
                        downloadable.free(reservation);
                    } catch (final Throwable e) {
                        LogSource.exception(logger, e);
                    }
                    try {
                        downloadable.addDownloadTime(System.currentTimeMillis() - getStartTimeStamp());
                    } catch (final Throwable e) {
                    }
                    downloadable.removePluginProgress(downloadPluginProgress);
                }
                HashResult result = onChunksReady();
                if (result != null) {
                    logger.info(result.getHashInfo().getType() + "-Check: " + (result.match() ? "ok" : "failed"));
                    if (result.match()) {
                        downloadable.setLinkStatusText(_JDT._.system_download_doCRC2_success(result.getHashInfo().getType()));
                    } else {
                        throw new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, _JDT._.system_download_doCRC2_failed(result.getHashInfo().getType()));
                    }
                }
                return handleErrors();
            } finally {
                downloadable.unlockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
                cleanupDownladInterface();
            }
        } catch (PluginException e) {
            error(e);
            throw e;
        } catch (Exception e) {
            if (e instanceof FileNotFoundException) {
                this.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, _JDT._.download_error_message_localio(e.getMessage()), LinkStatus.VALUE_LOCAL_IO_ERROR));
            } else {
                LogSource.exception(logger, e);
            }
            throw e;
        } finally {
            cleanupDownladInterface();
        }
    }

    /**
     * Gibt die Anzahl der verwendeten Chunks zurueck
     */
    public int getChunkNum() {
        return chunkNum;
    }

    /**
     * Gibt eine bestmoegliche abschaetzung der Dateigroesse zurueck
     */
    protected long getFileSize() {
        long verifiedFileSize = downloadable.getVerifiedFileSize();
        if (verifiedFileSize >= 0) {
            return verifiedFileSize;
        }
        if (connection != null) {
            if (connection.getRange() != null) {
                /* we have a range response, let's use it */
                if (connection.getRange()[2] > 0) {
                    return connection.getRange()[2];
                }
            }
            if (connection.getRequestProperty("Range") == null && connection.getLongContentLength() > 0 && connection.isOK()) {
                /* we have no range request and connection is okay, so we can use the content-length */
                return connection.getLongContentLength();
            }
        }
        if (downloadable.getDownloadTotalBytes() > 0) {
            return downloadable.getDownloadTotalBytes();
        }
        return -1;
    }

    public Request getRequest() {
        return this.request;
    }

    @Override
    public URLConnectionAdapter getConnection() {
        return this.connection;
    }

    @Override
    public boolean externalDownloadStop() {
        return abort.get();
    }

    /** signal that we stopped download external */
    public void stopDownload() {
        if (abort.getAndSet(true) == false) {
            logger.info("externalStop recieved");
            terminate(false);
        }
    }

    protected void waitForChunks() {
        try {
            logger.finer("Wait for chunks");
            mainLoop: while (true) {
                try {
                    synchronized (chunks) {
                        for (RAFChunk chunk : chunks) {
                            if (chunk.isAlive() && chunk.isRunning()) {
                                chunks.wait(1000);
                                continue mainLoop;
                            }
                        }
                    }
                    break;
                } catch (InterruptedException e) {
                    terminate(true);
                }
            }
            /* set the *real loaded* bytes here */
            downloadable.setDownloadBytesLoaded(totalLinkBytesLoaded);
        } catch (Throwable e) {
            LogSource.exception(logger, e);
        }
    }

    /**
     * terminate this DownloadInterface, abort all running chunks
     */
    protected void terminate(boolean forcedWaiting) {
        if (terminated.getAndSet(true) == false || forcedWaiting) {
            if (!externalDownloadStop()) {
                logger.severe("A critical Downloaderror occured. Terminate...");
            }
            while (true) {
                synchronized (chunks) {
                    boolean wait = false;
                    for (RAFChunk chunk : chunks) {
                        if (chunk.isRunning()) {
                            chunk.closeConnections();
                            if (chunk == Thread.currentThread()) {
                                /**
                                 * a thread should not wait for its own death :)
                                 */
                                continue;
                            } else {
                                wait = true;
                            }
                        }
                    }
                    if (wait) {
                        try {
                            chunks.wait(1000);
                        } catch (InterruptedException e) {
                            LogSource.exception(logger, e);
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }
        }
    }

    protected boolean connectResumable() throws IOException {
        // TODO: endrange pruefen
        long[] chunkProgress = downloadable.getChunksProgress();
        String start, end;
        start = end = "";
        boolean rangeRequested = false;
        logger.info("chunksProgress: " + Arrays.toString(chunkProgress));
        if (downloadable.getVerifiedFileSize() > 0) {
            start = chunkProgress[0] == 0 ? "0" : (chunkProgress[0] + 1) + "";
            end = (getFileSize() / chunkProgress.length) + "";
        } else {
            start = chunkProgress[0] == 0 ? "0" : (chunkProgress[0] + 1) + "";
            end = chunkProgress.length > 1 ? (chunkProgress[1] + 1) + "" : "";
        }
        if (downloadable.getVerifiedFileSize() < 0 && start.equals("0")) {
            logger.info("rangeless resumable connect");
            rangeRequested = false;
            request.getHeaders().remove("Range");
        } else {
            rangeRequested = true;
            if (start.equalsIgnoreCase(end)) {
                logger.info("WTF, start equals end! Workaround: maybe manipulating the startRange?! it's about time for new downloadcore!");
            }
            request.getHeaders().put("Range", "bytes=" + start + "-" + end);
        }
        browser.openRequestConnection(request, false);
        return rangeRequested;
    }

    /**
     * Setzt den aktuellen readtimeout(nur vor dem dl start)
     */
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * Setzt vor ! dem download dden requesttimeout. Sollte nicht zu niedrig sein weil sonst das automatische kopieren der Connections fehl
     * schlaegt.,
     */
    public void setRequestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    protected HashResult onChunksReady() throws Exception {
        logger.info("Close connections if they are not closed yet");
        HashResult result = null;
        try {
            for (RAFChunk c : chunks) {
                c.closeConnections();
            }
        } finally {
            cleanupDownladInterface();
        }
        if (!handleErrors()) {
            return result;
        }
        /* lets check the hash/crc/sfv */
        if (JsonConfig.create(GeneralSettings.class).isHashCheckEnabled() && downloadable.isHashCheckEnabled()) {
            synchronized (HASHCHECKLOCK) {
                /*
                 * we only want one hashcheck running at the same time. many finished downloads can cause heavy diskusage here
                 */
                HashInfo hashInfo = downloadable.getHashInfo();
                result = downloadable.getHashResult(hashInfo, outputPartFile);
                downloadable.setHashResult(result);
            }
        }
        downloadable.setVerifiedFileSize(outputPartFile.length());
        boolean renameOkay = downloadable.rename(outputPartFile, outputCompleteFile);
        if (renameOkay) {

            /* save absolutepath as final location property */
            // downloadable.setFinalFileOutput(outputCompleteFile.getAbsolutePath());
            try {
                Date last = TimeFormatter.parseDateString(connection.getHeaderField("Last-Modified"));
                if (last != null && JsonConfig.create(GeneralSettings.class).isUseOriginalLastModified()) {
                    /* set original lastModified timestamp */
                    outputCompleteFile.setLastModified(last.getTime());
                } else {
                    /* set current timestamp as lastModified timestamp */
                    outputCompleteFile.setLastModified(System.currentTimeMillis());
                }
            } catch (final Throwable e) {
                LogSource.exception(logger, e);
            }
        } else {
            error(new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, _JDT._.system_download_errors_couldnotrename(), LinkStatus.VALUE_LOCAL_IO_ERROR));
        }

        return result;
    }

    protected void setupChunks() throws Exception {
        try {
            if (isRangeRequestSupported() && checkResumabled()) {
                logger.finer("Setup resume");
                this.resumedDownload = true;
                this.setupResume();
            } else {
                logger.finer("Setup virgin download");
                this.resumedDownload = false;
                this.setupVirginStart();
            }
        } catch (Exception e) {
            throw e;
        }
    }

    public boolean isResumedDownload() {
        return resumedDownload;
    }

    private void setupVirginStart() throws FileNotFoundException {
        RAFChunk chunk;
        totalLinkBytesLoaded = 0;
        downloadable.setDownloadBytesLoaded(0);
        long fileSize = getFileSize();
        long partSize = 0;
        if (fileSize < 0) {
            /* unknown filesize handling */
            if (getChunkNum() > 1) {
                logger.warning("Unknown FileSize->reset chunks to 1 and start at beginning");
                setChunkNum(1);
            } else {
                logger.warning("Unknown FileSize->start at beginning");
            }
        } else if (fileSize == 0) {
            /* zero filesize handling */
            if (getChunkNum() > 1) {
                logger.warning("Zero FileSize->reset chunks to 1 and start at beginning");
                setChunkNum(1);
            } else {
                logger.warning("Zero FileSize->start at beginning");
            }
        } else {
            partSize = fileSize / getChunkNum();
            if (connection.getRange() != null) {
                if ((connection.getRange()[1] == connection.getRange()[2] - 1) || (connection.getRange()[1] == connection.getRange()[2])) {
                    logger.warning("Chunkload protection. this may cause traffic errors");
                    partSize = fileSize / getChunkNum();
                } else {
                    // Falls schon der 1. range angefordert wurde.... werden die
                    // restlichen chunks angepasst
                    partSize = (fileSize - connection.getLongContentLength()) / (getChunkNum() - 1);
                }
            }
            if (partSize <= 0) {
                logger.warning("Filesize is " + fileSize + " but partSize is " + partSize + "-> reset chunks to 1");
                setChunkNum(1);
            }
        }
        logger.finer("Start Download in " + getChunkNum() + " chunks. Chunksize: " + partSize);
        downloadable.setChunksProgress(new long[chunkNum]);

        int start = 0;

        long rangePosition = 0;
        int id = 0;
        if (connection.getRange() != null && connection.getRange()[1] != connection.getRange()[2] - 1) {
            // Erster range schon angefordert

            chunk = new RAFChunk(0, rangePosition = connection.getRange()[1], connection, this, downloadable, id++);
            rangePosition++;
            logger.finer("Setup chunk 0: " + chunk);
            addChunk(chunk);
            start++;
        }

        for (int i = start; i < getChunkNum(); i++) {
            if (i == getChunkNum() - 1) {
                chunk = new RAFChunk(rangePosition, -1, connection, this, downloadable, id++);
            } else {
                chunk = new RAFChunk(rangePosition, rangePosition + partSize - 1, connection, this, downloadable, id++);
                rangePosition = rangePosition + partSize;
            }
            logger.finer("Setup chunk " + i + ": " + chunk);
            addChunk(chunk);
        }

    }

    /**
     * Fuegt einen Chunk hinzu und startet diesen
     * 
     * @param chunk
     */
    protected void addChunk(RAFChunk chunk) {
        synchronized (chunks) {
            chunks.add(chunk);
            chunk.startChunk();
        }
    }

    protected void addChunk(Chunk chunk) {
        throw new WTFException("This should not happen!");
    }

    protected boolean writeChunkBytes(final Chunk chunk) {
        throw new WTFException("This should not happen!");
    }

    private void createOutputChannel() throws SkipReasonException {
        try {
            String fileOutput = downloadable.getFileOutput();
            logger.info("createOutputChannel for " + fileOutput);
            String finalFileOutput = downloadable.getFinalFileOutput();
            outputCompleteFile = new File(fileOutput);
            outputFinalCompleteFile = outputCompleteFile;
            if (!fileOutput.equals(finalFileOutput)) {
                outputFinalCompleteFile = new File(finalFileOutput);
            }
            outputPartFile = new File(downloadable.getFileOutputPart());
            outputPartFileRaf = new RandomAccessFile(outputPartFile, "rw");
        } catch (Exception e) {
            LogSource.exception(logger, e);
            throw new SkipReasonException(SkipReason.INVALID_DESTINATION, e);
        }
    }

    private void setupResume() throws FileNotFoundException {
        long parts = getFileSize() / getChunkNum();
        logger.info("Resume: " + getFileSize() + " partsize: " + parts);
        RAFChunk chunk;
        int id = 0;
        for (int i = 0; i < getChunkNum(); i++) {
            if (i == getChunkNum() - 1) {
                chunk = new RAFChunk(downloadable.getChunksProgress()[i] == 0 ? 0 : downloadable.getChunksProgress()[i] + 1, -1, connection, this, downloadable, id++);
                chunk.setLoaded((downloadable.getChunksProgress()[i] - i * parts + 1));
            } else {
                chunk = new RAFChunk(downloadable.getChunksProgress()[i] == 0 ? 0 : downloadable.getChunksProgress()[i] + 1, (i + 1) * parts - 1, connection, this, downloadable, id++);
                chunk.setLoaded((downloadable.getChunksProgress()[i] - i * parts + 1));
            }
            logger.finer("Setup chunk " + i + ": " + chunk);
            addChunk(chunk);
        }

    }

    @Override
    public long getTotalLinkBytesLoadedLive() {
        return totalLinkBytesLoadedLive.get();
    }

    public synchronized void setTotalLinkBytesLoaded(long loaded) {
        totalLinkBytesLoaded = loaded;
        totalLinkBytesLoadedLive.set(loaded);
    }

    protected synchronized void addToTotalLinkBytesLoaded(long block, boolean updateLiveData) {
        totalLinkBytesLoaded += block;
        if (updateLiveData) {
            totalLinkBytesLoadedLive.addAndGet(block);
        }
    }

    protected boolean writeChunkBytes(RAFChunk chunk) {
        try {
            synchronized (outputPartFile) {
                outputPartFileRaf.seek(chunk.getWritePosition());
                outputPartFileRaf.write(chunk.buffer.getInternalBuffer(), 0, chunk.buffer.size());
                if (chunk.getID() >= 0) {
                    downloadable.getChunksProgress()[chunk.getID()] = chunk.getCurrentBytesPosition() - 1;
                }
            }
            DownloadController.getInstance().requestSaving();
            return true;
        } catch (Exception e) {
            LogSource.exception(logger, e);
            error(new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, Exceptions.getStackTrace(e), LinkStatus.VALUE_LOCAL_IO_ERROR));
            return false;
        }
    }

    public void cleanupDownladInterface() {
        try {
            downloadable.removeConnectionHandler(this.getManagedConnetionHandler());
        } catch (final Throwable e) {
        }
        try {
            this.connection.disconnect();
        } catch (Throwable e) {
        }
        closeOutputChannel();
    }

    private void closeOutputChannel() {
        try {
            RandomAccessFile loutputPartFileRaf = outputPartFileRaf;
            if (loutputPartFileRaf != null) {
                logger.info("Close File. Let AV programs run");
                loutputPartFileRaf.close();
            }
        } catch (Throwable e) {
            LogSource.exception(logger, e);
        } finally {
            outputPartFileRaf = null;
        }
    }

    public Logger getLogger() {
        return logger;
    }

    @Override
    public void close() {
    }

    @Override
    public Downloadable getDownloadable() {
        return downloadable;
    }

}