package com.compomics.denovogui;

import com.compomics.denovogui.execution.Job;
import com.compomics.denovogui.execution.jobs.DirecTagJob;
import com.compomics.denovogui.execution.jobs.NovorJob;
import com.compomics.denovogui.execution.jobs.PNovoJob;
import com.compomics.denovogui.execution.jobs.PepNovoJob;
import com.compomics.denovogui.io.FileProcessor;
import com.compomics.denovogui.io.PepNovoModificationFile;
import com.compomics.software.CompomicsWrapper;
import com.compomics.util.Util;
import com.compomics.util.exceptions.ExceptionHandler;
import com.compomics.util.experiment.identification.Advocate;
import com.compomics.util.experiment.identification.identification_parameters.SearchParameters;
import com.compomics.util.experiment.identification.identification_parameters.tool_specific.PepnovoParameters;
import com.compomics.util.experiment.massspectrometry.SpectrumFactory;
import com.compomics.util.gui.waiting.waitinghandlers.WaitingHandlerCLIImpl;
import com.compomics.util.preferences.UtilitiesUserPreferences;
import com.compomics.util.waiting.WaitingHandler;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;

/**
 * This handle the searches with the search parameters taken from the GUI or the
 * command line.
 *
 * @author Marc Vaudel
 * @author Thilo Muth
 * @author Harald Barsnes
 */
public class DeNovoSequencingHandler {

    /**
     * The PepNovo folder.
     */
    private File pepNovoFolder;
    /**
     * The DirecTag folder.
     */
    private File direcTagFolder;
    /**
     * The pNovo folder.
     */
    private File pNovoFolder;
    /**
     * The Novor folder.
     */
    private File novorFolder;
    /**
     * If true, PepNovo will be run.
     */
    private boolean enablePepNovo = true;
    /**
     * If true, DirecTag will be used.
     */
    private boolean enableDirecTag = true;
    /**
     * If true, pNovo+ will be run.
     */
    private boolean enablePNovo = false;
    /**
     * If true, Novor will be run.
     */
    private boolean enableNovor = false;
    /**
     * Default PTM selection.
     */
    public static final String DENOVOGUI_COMFIGURATION_FILE = "DeNovoGUI_configuration.txt";
    /**
     * Modification file.
     */
    private static String MODIFICATIONS_FILE = "resources/conf/denovogui_mods.xml";
    /**
     * User modification file.
     */
    private static String USER_MODIFICATIONS_FILE = "resources/conf/denovogui_usermods.xml";
    /**
     * The enzyme file.
     */
    private static String ENZYME_FILE = "resources/conf/enzymes.xml";
    /**
     * The name of the parameters file saved by default.
     */
    public final static String parametersFileName = "denovoGUI.par";
    /**
     * The chunk files.
     */
    private ArrayList<File> chunkFiles;
    /**
     * Number of threads to use for the processing.
     */
    private int nThreads = Runtime.getRuntime().availableProcessors(); // @TODO: should be moved to user preferences?
    /**
     * The thread executor.
     */
    private ExecutorService threadExecutor = null;
    /**
     * The spectrum factory.
     */
    private SpectrumFactory spectrumFactory = SpectrumFactory.getInstance();
    /**
     * The job queue.
     */
    private Deque<Job> jobs;
    /**
     * The exception handler.
     */
    private ExceptionHandler exceptionHandler;

    /**
     * Constructor.
     *
     * @param pepNovoFolder the PepNovo+ folder
     * @param direcTagFolder the DirecTag folder
     * @param pNovoFolder the pNovo folder
     * @param novorFolder the Novor folder
     */
    public DeNovoSequencingHandler(File pepNovoFolder, File direcTagFolder, File pNovoFolder, File novorFolder) {
        this.pepNovoFolder = pepNovoFolder;
        this.direcTagFolder = direcTagFolder;
        this.pNovoFolder = pNovoFolder;
        this.novorFolder = novorFolder;
    }

    /**
     * Starts the sequencing for a list of files which will be processed
     * sequentially.
     *
     * @param spectrumFiles the spectrum files to process
     * @param searchParameters the search parameters
     * @param outputFolder the output folder
     * @param pepNovoExeTitle the name of the PepNovo+ executable
     * @param direcTagExeTitle the name of the DirecTag executable
     * @param pNovoExeTitle the name of the pNovo+ executable
     * @param novorExeTitle the name of the Novor executable
     * @param enablePepNovo run PepNovo?
     * @param enableDirecTag run DirecTag?
     * @param enablePNovo run pNovo?
     * @param enableNovor run Novor?
     * @param waitingHandler the waiting handler
     * @param exceptionHandler the exception handler to use when an exception is
     * caught
     *
     * @throws IOException thrown if there is an IO issue
     * @throws ClassNotFoundException thrown if the search parameters cannot be
     * processed from file
     */
    public void startSequencing(List<File> spectrumFiles, SearchParameters searchParameters, File outputFolder, String pepNovoExeTitle, String direcTagExeTitle, String pNovoExeTitle, String novorExeTitle,
            boolean enablePepNovo, boolean enableDirecTag, boolean enablePNovo, boolean enableNovor, WaitingHandler waitingHandler, ExceptionHandler exceptionHandler) throws IOException, ClassNotFoundException {

        this.enablePepNovo = enablePepNovo;
        this.enableDirecTag = enableDirecTag;
        this.enablePNovo = enablePNovo;
        this.enableNovor = enableNovor;
        this.exceptionHandler = exceptionHandler;

        long startTime = System.nanoTime();
        waitingHandler.setMaxPrimaryProgressCounter(spectrumFactory.getNSpectra() + 2);
        waitingHandler.setSecondaryProgressCounterIndeterminate(true);

        // store the pepnovo to utilities ptm mapping
        PepnovoParameters pepnovoParameters = (PepnovoParameters) searchParameters.getIdentificationAlgorithmParameter(Advocate.pepnovo.getIndex());
        pepnovoParameters.setPepNovoPtmMap(PepNovoModificationFile.getInvertedModIdMap());

        if (searchParameters.getParametersFile() != null) {
            SearchParameters.saveIdentificationParameters(searchParameters, searchParameters.getParametersFile());
        }

        // write the modification file
        try {
            File folder = new File(pepNovoFolder, "Models");
            PepNovoModificationFile.writeFile(folder, searchParameters.getPtmSettings());
        } catch (Exception e) {
            waitingHandler.appendReport("An error occurred while writing the modification file: " + e.getMessage(), true, true);
            exceptionHandler.catchException(e);
            waitingHandler.setRunCanceled();
            return;
        }

        // back-up the parameters
        try {
            SearchParameters.saveIdentificationParameters(searchParameters, new File(outputFolder, parametersFileName));
        } catch (Exception e) {
            waitingHandler.appendReport("An error occurred while writing the sequencing parameters: " + e.getMessage(), true, true);
            exceptionHandler.catchException(e);
            waitingHandler.setRunCanceled();
            return;
        }

        // get the number of available threads
        String fileEnding = "";
        if (spectrumFactory.getMgfFileNames().size() > 1) {
            fileEnding = "s";
        }
        String threadEnding = "";
        if (nThreads > 1) {
            threadEnding = "s";
        }
        waitingHandler.appendReport("Starting de novo sequencing: " + spectrumFactory.getNSpectra() + " spectra in "
                + spectrumFactory.getMgfFileNames().size() + " file" + fileEnding + " using " + nThreads + " thread" + threadEnding + ".", true, true);
        waitingHandler.appendReportEndLine();
        waitingHandler.increasePrimaryProgressCounter();

        for (File spectrumFile : spectrumFiles) {
            startSequencing(spectrumFile, searchParameters, outputFolder, pepNovoExeTitle, direcTagExeTitle, pNovoExeTitle, novorExeTitle, waitingHandler, spectrumFiles.size() > 1);
            if (waitingHandler.isRunCanceled()) {
                break;
            }
        }

        if (!waitingHandler.isRunCanceled()) {
            double elapsedTime = (System.nanoTime() - startTime) * 1.0e-9;
            waitingHandler.appendReport("Total sequencing time: " + Util.roundDouble(elapsedTime, 2) + " sec.", true, true);

            // check if we have any output files
            ArrayList<File> resultFiles = FileProcessor.getAllResultFiles(outputFolder, spectrumFiles, enablePepNovo, enableDirecTag, enablePNovo, enableNovor);

            if (resultFiles.isEmpty()) {
                waitingHandler.appendReportEndLine();
                waitingHandler.appendReport("The de novo sequencing did not generate any output files!", true, true);
                waitingHandler.setRunCanceled();
            } else {
                waitingHandler.setRunFinished();
            }
        }
    }

    /**
     * Starts the sequencing for a single file.
     *
     * @param spectrumFile the spectrum file to process
     * @param searchParameters the search parameters
     * @param outputFolder the output folder
     * @param pepNovoExeTitle the name of the PepNovo+ executable
     * @param direcTagExeTitle the name of the DirecTag executable
     * @param pNovoExeTitle the name of the pNovo+ executable
     * @param novorExeTitle the name of the Novor executable
     * @param waitingHandler the waiting handler
     * @param secondaryProgress if true the progress on the given file will be
     * displayed
     */
    private void startSequencing(File spectrumFile, SearchParameters searchParameters, File outputFolder, String pepNovoExeTitle,
            String direcTagExeTitle, String pNovoExeTitle, String novorExeTitle, WaitingHandler waitingHandler, boolean secondaryProgress) throws IOException {

        // start a fixed thread pool
        threadExecutor = Executors.newFixedThreadPool(nThreads);

        // job queue
        jobs = new ArrayDeque<Job>();
        try {
            int nSpectra = spectrumFactory.getNSpectra(spectrumFile.getName());
            int remaining = nSpectra % nThreads;
            int chunkSize = nSpectra / nThreads;
            String report = "Processing " + spectrumFile.getName() + " (" + nSpectra + " spectra, " + chunkSize;
            if (remaining > 0) {
                int maxSize = chunkSize + 1;
                report += "-" + maxSize;
            }
            report += " spectra per thread).";
            waitingHandler.appendReport(report, true, true);

            if (enablePepNovo) {
                waitingHandler.appendReport("Preparing the spectra.", true, true);
                chunkFiles = FileProcessor.chunkFile(spectrumFile, chunkSize, remaining, nSpectra, waitingHandler);
            }

            if (waitingHandler.isRunCanceled()) {
                return;
            }

            waitingHandler.setWaitingText("Processing " + spectrumFile.getName() + ".");
            if (secondaryProgress) {
                waitingHandler.resetSecondaryProgressCounter();
                waitingHandler.setMaxSecondaryProgressCounter(nSpectra);
            } else {
                waitingHandler.setSecondaryProgressCounterIndeterminate(true);
            }

            // verify that the file is chunked and use the entire if not
            boolean chunksuccess = true;
            if (enablePepNovo) {
                for (File chunkFile : chunkFiles) {
                    if (!chunkFile.exists()) {
                        chunksuccess = false;
                        waitingHandler.appendReport("Processing of the spectra failed. Only one thread will be used for PepNovo+.", true, true);
                    }
                }
            }

            if (!chunksuccess || enableDirecTag) {
                if (!spectrumFile.exists()) {
                    waitingHandler.appendReport("Spectrum file not found.", true, true);
                    return;
                }
            }

            // distribute the chunked spectra to the different PepNovo+ jobs
            if (enablePepNovo) {
                if (chunksuccess) {
                    for (File chunkFile : chunkFiles) {
                        PepNovoJob pepNovoJob = new PepNovoJob(pepNovoFolder, pepNovoExeTitle, chunkFile, outputFolder, searchParameters, waitingHandler, exceptionHandler);
                        jobs.add(pepNovoJob);
                    }
                } else {
                    PepNovoJob pepNovoJob = new PepNovoJob(pepNovoFolder, pepNovoExeTitle, spectrumFile, outputFolder, searchParameters, waitingHandler, exceptionHandler);
                    jobs.add(pepNovoJob);
                }
            }

            // add the DirecTag job only once - multithreading is done in the application itself
            if (enableDirecTag) {
                DirecTagJob direcTagJob = new DirecTagJob(direcTagFolder, direcTagExeTitle, spectrumFile, nThreads, outputFolder, searchParameters, waitingHandler, exceptionHandler);
                jobs.add(direcTagJob);
            }

            // add the pNovo+ job only once - multithreading is done in the application itself
            if (enablePNovo) {
                PNovoJob pNovoJob = new PNovoJob(pNovoFolder, pNovoExeTitle, spectrumFile, nThreads, outputFolder, searchParameters, waitingHandler, exceptionHandler);
                jobs.add(pNovoJob);
            }

            // add the Novor job only once - multithreading is done in the application itself
            if (enableNovor) {
                NovorJob novorJob = new NovorJob(novorFolder, spectrumFile, outputFolder, searchParameters, waitingHandler instanceof WaitingHandlerCLIImpl, waitingHandler, exceptionHandler);
                jobs.add(novorJob);
            }

        } catch (FileNotFoundException ex) {
            exceptionHandler.catchException(ex);
            return;
        } catch (IOException ex) {
            exceptionHandler.catchException(ex);
            return;
        }

        if (waitingHandler.isRunCanceled()) {
            return;
        }

        waitingHandler.appendReportEndLine();
        waitingHandler.appendReport("Starting de novo sequencing of " + spectrumFile.getName() + ".", true, true);
        waitingHandler.appendReportEndLine();

        // execute the jobs from the queue
        for (Job job : jobs) {
            job.writeCommand();
            threadExecutor.execute(job); // @TODO: one job at the time!!!
            if (waitingHandler.isRunCanceled()) {
                return;
            }
        }

        // wait for executor service to shutdown
        threadExecutor.shutdown();

        try {
            threadExecutor.awaitTermination(12, TimeUnit.HOURS);
        } catch (InterruptedException ex) {
            if (!waitingHandler.isRunCanceled()) {
                threadExecutor.shutdownNow();
                exceptionHandler.catchException(ex);
            }
        }

        if (waitingHandler.isRunCanceled()) {
            return;
        }

        waitingHandler.appendReportEndLine();

        waitingHandler.appendReport("Sequencing of " + spectrumFile.getName() + " finished.", true, true);
        waitingHandler.appendReportEndLine();

        waitingHandler.setSecondaryProgressCounterIndeterminate(true);

        if (enablePepNovo) {
            FileProcessor.mergeAndDeleteOutputFiles(FileProcessor.getOutFiles(outputFolder, chunkFiles));

            // delete the mgf file chunks
            FileProcessor.deleteChunkFiles(chunkFiles, waitingHandler);
        }
    }

    /**
     * Cancels the sequencing process.
     *
     * @param outputFolder the output folder
     * @param waitingHandler the waiting handler
     * @throws IOException thrown if the deletion of the chunk files fail
     */
    public void cancelSequencing(File outputFolder, WaitingHandler waitingHandler) throws IOException {
        if (jobs != null) {
            // cancel the jobs and delete temp .out files
            for (Job job : jobs) {
                job.cancel();
            }
        }
        if (threadExecutor != null) {
            threadExecutor.shutdown();
            try {
                threadExecutor.awaitTermination(12, TimeUnit.HOURS);
            } catch (InterruptedException ex) {
                if (waitingHandler.isRunCanceled()) {
                    threadExecutor.shutdownNow();
                    ex.printStackTrace();
                }
            }

            if (chunkFiles != null) {
                // delete the output files
                FileProcessor.deleteChunkFiles(FileProcessor.getOutFiles(outputFolder, chunkFiles), waitingHandler);

                // delete the mgf file chunks
                FileProcessor.deleteChunkFiles(chunkFiles, waitingHandler);
            }
        }
    }

    /**
     * Returns the path to the jar file.
     *
     * @return the path to the jar file
     */
    public String getJarFilePath() {
        return CompomicsWrapper.getJarFilePath(this.getClass().getResource("DeNovoSequencingHandler.class").getPath(), "DeNovoGUI");
    }

    /**
     * Returns the user defined enzymes file.
     *
     * @param jarFolder the folder containing the jar file
     * @return the user defined enzymes file
     */
    public static File getEnzymesFile(String jarFolder) {
        File result = new File(jarFolder, ENZYME_FILE);
        if (!result.exists()) {
            JOptionPane.showMessageDialog(null, ENZYME_FILE + " not found.", "Enzymes File Error", JOptionPane.ERROR_MESSAGE);
        }
        return result;
    }

    /**
     * Get the number of threads to use for the processing.
     *
     * @return the mgfNSpectra
     */
    public int getNThreads() {
        return nThreads;
    }

    /**
     * Set the number of threads to use.
     *
     * @param nThreads the nThreads to set
     */
    public void setNThreads(int nThreads) {
        this.nThreads = nThreads;
    }

    /**
     * Returns the file containing the enzymes.
     *
     * @return the file containing the enzymes
     */
    public static String getEnzymeFile() {
        return ENZYME_FILE;
    }

    /**
     * Sets the file containing the enzymes.
     *
     * @param enzymeFile the file containing the enzymes
     */
    public static void setEnzymeFile(String enzymeFile) {
        DeNovoSequencingHandler.ENZYME_FILE = enzymeFile;
    }

    /**
     * Returns the file used for default modifications pre-loading.
     *
     * @return the file used for default modifications pre-loading
     */
    public static String getDefaultModificationFile() {
        return MODIFICATIONS_FILE;
    }

    /**
     * Sets the file used for default modifications pre-loading.
     *
     * @param modificationFile the file used for default modifications
     * pre-loading
     */
    public static void setDefaultModificationFile(String modificationFile) {
        DeNovoSequencingHandler.MODIFICATIONS_FILE = modificationFile;
    }

    /**
     * Returns the file used for user modifications pre-loading.
     *
     * @return the file used for user modifications pre-loading
     */
    public static String getUserModificationFile() {
        return USER_MODIFICATIONS_FILE;
    }

    /**
     * Sets the file used for user modifications pre-loading.
     *
     * @param modificationFile the file used for user modifications pre-loading
     */
    public static void setUserModificationFile(String modificationFile) {
        DeNovoSequencingHandler.USER_MODIFICATIONS_FILE = modificationFile;
    }
}
