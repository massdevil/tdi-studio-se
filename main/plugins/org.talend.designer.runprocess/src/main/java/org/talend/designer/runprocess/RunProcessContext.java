// ============================================================================
//
// Copyright (C) 2006-2015 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.designer.runprocess;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.IProgressService;
import org.talend.commons.ui.runtime.exception.ExceptionHandler;
import org.talend.commons.ui.swt.dialogs.EventLoopProgressMonitor;
import org.talend.core.language.ECodeLanguage;
import org.talend.core.language.LanguageManager;
import org.talend.core.model.process.IConnection;
import org.talend.core.model.process.IContext;
import org.talend.core.model.process.IElementParameter;
import org.talend.core.model.process.INode;
import org.talend.core.model.process.IPerformance;
import org.talend.core.model.process.IProcess;
import org.talend.core.model.process.IProcess2;
import org.talend.core.model.process.ITargetExecutionConfig;
import org.talend.core.model.process.TraceData;
import org.talend.core.model.properties.Property;
import org.talend.core.model.runprocess.IEclipseProcessor;
import org.talend.core.model.runprocess.data.PerformanceData;
import org.talend.designer.core.model.components.EParameterName;
import org.talend.designer.core.ui.editor.jobletcontainer.JobletContainer;
import org.talend.designer.core.ui.editor.nodes.Node;
import org.talend.designer.core.utils.ConnectionUtil;
import org.talend.designer.core.utils.ParallelExecutionUtils;
import org.talend.designer.runprocess.ProcessMessage.MsgType;
import org.talend.designer.runprocess.i18n.Messages;
import org.talend.designer.runprocess.prefs.RunProcessPrefsConstants;
import org.talend.designer.runprocess.prefs.RunProcessTokenCollector;
import org.talend.designer.runprocess.trace.TraceConnectionsManager;
import org.talend.designer.runprocess.ui.ProcessContextComposite;
import org.talend.designer.runprocess.ui.actions.ClearPerformanceAction;
import org.talend.designer.runprocess.ui.actions.ClearTraceAction;
import org.talend.repository.ui.utils.Log4jPrefsSettingManager;
import org.talend.utils.network.FreePortFinder;

import routines.system.NoHeaderObjectInputStream;
import routines.system.NoHeaderObjectOutputStream;
import routines.system.TraceDataBean;
import routines.system.TraceStatusBean;

/**
 * Context of a running process. <br/>
 * 
 * $Id$
 * 
 * 
 */
public class RunProcessContext {

    private static final int STRING_LENGTH = 256;

    public static final String PROP_RUNNING = "RunProcessContext.Running"; //$NON-NLS-1$

    public static final String PROP_MONITOR = "RunProcessContext.MonitorPerf"; //$NON-NLS-1$

    public static final String TRACE_MONITOR = "RunProcessContext.MonitorTrace"; //$NON-NLS-1$

    private static final String PROR_SWITCH_TIME = "RunProcesscontext.Message.Watch"; //$NON-NLS-1$

    public static final String PREVIOUS_ROW = "RunProcessContext.PreviousRow";

    public static final String BREAKPOINT_BAR = "RunProcessContext.breakPoint";

    private static final String WATCH_PARAM = "--watch"; //$NON-NLS-1$

    private static final String LOG4J_ENABLE = "--applyLog4jToChildren";

    private static final String LOG4J_LEVEL_ARG = "--log4jLevel=";

    private static final String LOG4J_DEFAULT_LEVEL = "info";

    public static final String NEXTBREAKPOINT = "RunProcessContext.NextBreakpoint";

    private boolean watchAllowed;

    private Boolean nextBreakpoint = false;

    /** Change property listeners. */
    private final transient PropertyChangeSupport pcsDelegate;

    /** The process. */
    private final IProcess2 process;

    /** The selected context to run process with. */
    private IContext selectedContext;

    /** The selected server configuration to run process with. */
    private ITargetExecutionConfig selectedTargetExecutionConfig;

    /** Performance monitoring activated. */
    public boolean monitorPerf;

    /** Trace monitoring activated. */
    private boolean monitorTrace;

    private boolean selectAllTrace = false;

    /** Is process running. */
    private boolean running;

    /** The executing process. */
    private Process ps;

    /** Monitor of the running process. */
    private IProcessMonitor psMonitor;

    /** Monitor of the running process. */
    private PerformanceMonitor perfMonitor;

    /** Monitor for Traces of the running process. */
    private TraceMonitor traceMonitor;

    /** Kill is in progress. */
    private boolean killing;

    private boolean lastIsRow = false;

    private final IProcessMessageManager processMessageManager;

    private int statsPort = IProcessor.NO_STATISTICS;

    private int tracesPort = IProcessor.NO_TRACES;

    private org.eclipse.debug.core.model.IProcess debugProcess;

    private boolean saveBeforeRun;

    private boolean clearBeforeExec = true;

    private boolean isTracPause = false;

    private boolean isBasicRun = false;

    private boolean startingMessageWritten;

    private boolean useCustomLevel = false;

    private String log4jLevel;

    private List<PerformanceMonitor> perMonitorList = new ArrayList<PerformanceMonitor>();

    /** trace mananger */
    private TraceConnectionsManager traceConnectionsManager;

    /**
     * Constrcuts a new RunProcessContext.
     * 
     * @param process The process.
     */
    public RunProcessContext(IProcess2 process) {
        super();

        this.process = process;
        selectedContext = process.getContextManager().getDefaultContext();

        pcsDelegate = new PropertyChangeSupport(this);
        this.processMessageManager = new ProcessMessageManager();

        setMonitorPerf(RunProcessPlugin.getDefault().getPreferenceStore().getBoolean(RunProcessPrefsConstants.ISSTATISTICSRUN));
        setMonitorTrace(RunProcessPlugin.getDefault().getPreferenceStore().getBoolean(RunProcessPrefsConstants.ISTRACESRUN));
        setWatchAllowed(RunProcessPlugin.getDefault().getPreferenceStore().getBoolean(RunProcessPrefsConstants.ISEXECTIMERUN));
        setSaveBeforeRun(RunProcessPlugin.getDefault().getPreferenceStore().getBoolean(RunProcessPrefsConstants.ISSAVEBEFORERUN));
        setClearBeforeExec(RunProcessPlugin.getDefault().getPreferenceStore()
                .getBoolean(RunProcessPrefsConstants.ISCLEARBEFORERUN));
        setLog4jLevel(RunProcessPlugin.getDefault().getPreferenceStore().getString(RunProcessPrefsConstants.LOG4JLEVEL));
        setUseCustomLevel(RunProcessPlugin.getDefault().getPreferenceStore().getBoolean(RunProcessPrefsConstants.CUSTOMLOG4J));
    }

    public synchronized void addPropertyChangeListener(PropertyChangeListener l) {
        if (l == null) {
            throw new IllegalArgumentException();
        }

        processMessageManager.addPropertyChangeListener(l);
        pcsDelegate.addPropertyChangeListener(l);
    }

    protected void firePropertyChange(String property, Object oldValue, Object newValue) {
        if (pcsDelegate.hasListeners(property)) {
            pcsDelegate.firePropertyChange(property, oldValue, newValue);
        }
    }

    public synchronized void removePropertyChangeListener(PropertyChangeListener l) {
        if (l != null) {
            pcsDelegate.removePropertyChangeListener(l);
            processMessageManager.removePropertyChangeListener(l);
        }
    }

    public IProcess2 getProcess() {
        return process;
    }

    public void clearMessages() {
        processMessageManager.clearMessages();
    }

    public void switchTime() {
        // TODO should do something here.
        firePropertyChange(PROR_SWITCH_TIME, "true", "false"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public Collection<IProcessMessage> getMessages() {
        return processMessageManager.getMessages();
    }

    public void addMessage(IProcessMessage message) {
        processMessageManager.addMessage(message);
    }

    /**
     * Getter for monitorPerf.
     * 
     * @return the monitorPerf
     */
    public boolean isMonitorPerf() {
        return this.monitorPerf;
    }

    /**
     * Sets the monitorPerf.
     * 
     * @param monitorPerf the monitorPerf to set
     */
    public void setMonitorPerf(boolean monitorPerf) {
        if (!monitorPerf) {
            System.out.println();
        }
        if (this.monitorPerf != monitorPerf) {
            this.monitorPerf = monitorPerf;
            firePropertyChange(PROP_MONITOR, Boolean.valueOf(!monitorPerf), Boolean.valueOf(monitorPerf));
        }
    }

    /**
     * Getter for monitorTrace.
     * 
     * @return the monitorTrace
     */
    public boolean isMonitorTrace() {
        return this.monitorTrace;
    }

    /**
     * Sets the monitorTrace.
     * 
     * @param monitorTrace the monitorTraceto set
     */
    public void setMonitorTrace(boolean monitorTrace) {
        if (this.monitorTrace != monitorTrace) {
            this.monitorTrace = monitorTrace;
            if (process instanceof IProcess2) {
                process.setNeedRegenerateCode(true);
            }

            firePropertyChange(TRACE_MONITOR, Boolean.valueOf(!monitorTrace), Boolean.valueOf(monitorTrace));
        }
    }

    /**
     * 
     * ggu Comment method "hasConnectionTrace".
     * 
     * bug 11227
     */
    protected boolean hasConnectionTrace() {
        for (INode node : process.getGraphicalNodes()) {
            List<? extends IConnection> outgoingConnections = node.getOutgoingConnections();
            for (IConnection conn : outgoingConnections) {
                if (conn.isActivate() && conn.isTraceConnection()) {
                    return true;
                }
            }

            if ((node instanceof Node) && ((Node) node).isJoblet()) {
                for (INode jnode : node.getComponent().getProcess().getGraphicalNodes()) {
                    List<? extends IConnection> joutgoingConnections = jnode.getOutgoingConnections();
                    for (IConnection conn : joutgoingConnections) {
                        if (conn.isActivate() && conn.isTraceConnection()) {
                            return true;
                        }
                    }

                }
            }
        }
        return false;

    }

    /**
     * Getter for running.
     * 
     * @return the running
     */
    public boolean isRunning() {
        return this.running;
    }

    /**
     * Sets the running.
     * 
     * @param running the running to set
     */
    public void setRunning(boolean running) {
        if (this.running != running) {
            this.running = running;
            firePropertyChange(PROP_RUNNING, Boolean.valueOf(!running), Boolean.valueOf(running));
        }
    }

    public IContext getSelectedContext() {
        return selectedContext;
    }

    public void setSelectedContext(IContext context) {
        selectedContext = context;
    }

    Thread processMonitorThread;

    private boolean nextRow = false;

    private boolean priviousRow = false;

    private void showErrorMassage(String category) {
        String title = Messages.getString("RunProcessContext.PortErrorTitle", category); //$NON-NLS-1$
        String message = Messages.getString("RunProcessContext.PortErrorMessage", category.toLowerCase()); //$NON-NLS-1$
        MessageDialog.openError(PlatformUI.getWorkbench().getDisplay().getActiveShell(), title, message);
    }

    private void testPort() {

        if (monitorPerf) {
            try {
                findNewStatsPort();
            } catch (Exception e) {
                statsPort = IProcessor.NO_STATISTICS;
            }
            if (getStatisticsPort() == IProcessor.NO_STATISTICS) {
                showErrorMassage(Messages.getString("RunProcessContext.PortErrorStats")); //$NON-NLS-1$
                // disable the check.
                setMonitorPerf(false);
            }

        } else {
            statsPort = IProcessor.NO_STATISTICS;
        }
        if (monitorTrace) {
            try {
                findNewTracesPort();
            } catch (Exception e) {
                tracesPort = IProcessor.NO_TRACES;
            }
            if (getTracesPort() == IProcessor.NO_TRACES) {
                showErrorMassage(Messages.getString("RunProcessContext.PortErrorTraces")); //$NON-NLS-1$
                this.monitorTrace = false;
                // disable the check.
                setMonitorTrace(false);

            }
        } else {
            tracesPort = IProcessor.NO_TRACES;
        }

    }

    /**
     * 
     * cLi Comment method "allowMonitorTrace".
     * 
     * feature 6355, enable trace.
     * 
     * about the variable "monitorTrace", It used for a global trace.
     */
    public boolean allowMonitorTrace() {
        return this.monitorTrace;
    }

    /**
     * 
     * cLi Comment method "checkTraces".
     * 
     * feature 6355
     */
    private void checkTraces() {
        // connection settings for traces.
        boolean found = false;
        if (this.monitorTrace) {
            for (IConnection conn : this.getProcess().getAllConnections(null)) {
                IElementParameter param = conn.getElementParameter(EParameterName.TRACES_CONNECTION_ENABLE.getName());
                if (param != null && Boolean.TRUE.equals(param.getValue())) {
                    found = true;
                    break;
                }
            }
        }
        if (found) {
            this.monitorTrace = true;
        }
    }

    public boolean checkBreakpoint() {
        boolean found = false;
        // if (this.monitorTrace) {
        for (IConnection conn : this.getProcess().getAllConnections(null)) {
            IElementParameter param = conn.getElementParameter(EParameterName.ACTIVEBREAKPOINT.getName());
            IElementParameter param2 = conn.getElementParameter(EParameterName.TRACES_CONNECTION_ENABLE.getName());
            if (param != null && param2 != null && Boolean.TRUE.equals(param.getValue())
                    && Boolean.TRUE.equals(param2.getValue())) {
                found = true;
                break;
            }
        }
        // }
        return found;
    }

    public boolean checkParalization() {
        return ParallelExecutionUtils.isExistParallelConn(this.getProcess().getAllConnections(null));
    }

    /**
     * Launch the process.
     */
    public void exec(final Shell shell) {
        if (process instanceof org.talend.designer.core.ui.editor.process.Process) {
            org.talend.designer.core.ui.editor.process.Process prs = (org.talend.designer.core.ui.editor.process.Process) process;
            prs.checkDifferenceWithRepository();
        }
        checkTraces();

        if (ProcessContextComposite.promptConfirmLauch(shell, getSelectedContext(), process)) {
            if (getSelectedTargetExecutionConfig() == null || !getSelectedTargetExecutionConfig().isRemote()) {
                // tos run to collect
                IPreferenceStore preferenceStore = RunProcessPlugin.getDefault().getPreferenceStore();
                int num = preferenceStore.getInt(RunProcessTokenCollector.TOS_COUNT_RUNS.getPrefKey());
                preferenceStore.setValue(RunProcessTokenCollector.TOS_COUNT_RUNS.getPrefKey(), num + 1);
            }

            ClearPerformanceAction clearPerfAction = new ClearPerformanceAction();
            clearPerfAction.setProcess(process);
            clearPerfAction.run();

            ClearTraceAction clearTraceAction = new ClearTraceAction();
            clearTraceAction.setProcess(process);
            clearTraceAction.run();
            if (monitorPerf) {
                this.getStatisticsPort();
            }
            final IProcessor processor = getProcessor(process, process.getProperty());
            IProgressService progressService = PlatformUI.getWorkbench().getProgressService();

            try {
                progressService.run(false, true, new IRunnableWithProgress() {

                    @Override
                    public void run(final IProgressMonitor monitor) {

                        final IProgressMonitor progressMonitor = new EventLoopProgressMonitor(monitor);

                        progressMonitor.beginTask(Messages.getString("ProcessComposite.buildTask"), IProgressMonitor.UNKNOWN); //$NON-NLS-1$
                        try {
                            testPort();
                            // findNewStatsPort();
                            if (monitorPerf || monitorTrace) {
                                if (traceConnectionsManager != null) {
                                    traceConnectionsManager.clear();
                                }
                                traceConnectionsManager = new TraceConnectionsManager(process);
                                traceConnectionsManager.init();
                            }
                            final IContext context = getSelectedContext();
                            if (monitorPerf) {
                                clearThreads();
                                perfMonitor = new PerformanceMonitor();
                                new Thread(perfMonitor, "PerfMonitor_" + process.getLabel()).start(); //$NON-NLS-1$
                                perMonitorList.add(perfMonitor);
                            }
                            // findNewTracesPort();
                            if (monitorTrace) {
                                traceMonitor = new TraceMonitor();
                                new Thread(traceMonitor, "TraceMonitor_" + process.getLabel()).start(); //$NON-NLS-1$
                            }

                            final String watchParam = RunProcessContext.this.isWatchAllowed() ? WATCH_PARAM : null;
                            final String log4jRuntimeLevel = getLog4jRuntimeLevel();
                            processor.setContext(context);
                            ((IEclipseProcessor) processor).setTargetExecutionConfig(getSelectedTargetExecutionConfig());

                            ProcessorUtilities.generateCode(processor, process, context,
                                    getStatisticsPort() != IProcessor.NO_STATISTICS, getTracesPort() != IProcessor.NO_TRACES
                                            && hasConnectionTrace(), true, progressMonitor);
                            final boolean[] refreshUiAndWait = new boolean[1];
                            refreshUiAndWait[0] = true;
                            final Display display = shell.getDisplay();
                            new Thread(new Runnable() {

                                @Override
                                public void run() {
                                    display.syncExec(new Runnable() {

                                        @Override
                                        public void run() {
                                            try {
                                                startingMessageWritten = false;

                                                // see feature 0004820: The run
                                                // job doesn't verify if code is
                                                // correct
                                                // before launching
                                                if (!JobErrorsChecker.hasErrors(shell)) {
                                                    ps = processor.run(getStatisticsPort(), getTracesPort(), watchParam,
                                                            log4jRuntimeLevel, progressMonitor, processMessageManager);
                                                }

                                                if (ps != null && !progressMonitor.isCanceled()) {
                                                    setRunning(true);
                                                    psMonitor = createProcessMonitor(ps);

                                                    startingMessageWritten = true;

                                                    final String startingPattern = Messages
                                                            .getString("ProcessComposite.startPattern"); //$NON-NLS-1$
                                                    MessageFormat mf = new MessageFormat(startingPattern);
                                                    String welcomeMsg = mf.format(new Object[] { process.getLabel(), new Date() });
                                                    processMessageManager.addMessage(new ProcessMessage(MsgType.CORE_OUT,
                                                            welcomeMsg + "\r\n")); //$NON-NLS-1$
                                                    processMonitorThread = new Thread(psMonitor);
                                                    processMonitorThread.start();
                                                } else {
                                                    kill();
                                                    running = true;
                                                    setRunning(false);
                                                }
                                            } catch (Throwable e) {
                                                // catch any Exception or Error
                                                // to kill the process, see bug
                                                // 0003567
                                                running = true;
                                                Throwable cause = e.getCause();
                                                if (cause != null && cause.getClass().equals(InterruptedException.class)) {
                                                    setRunning(false);
                                                    addErrorMessage(e);
                                                } else {
                                                    ExceptionHandler.process(e);
                                                    addErrorMessage(e);
                                                    kill();
                                                }
                                            } finally {
                                                // progressMonitor.done();
                                                refreshUiAndWait[0] = false;
                                            }
                                        }
                                    });
                                }
                            }, "RunProcess_" + process.getLabel()).start(); //$NON-NLS-1$
                            while (refreshUiAndWait[0] && !progressMonitor.isCanceled()) {
                                if (!display.readAndDispatch()) {
                                    display.sleep();
                                }
                                synchronized (this) {
                                    try {
                                        final long waitTime = 50;
                                        wait(waitTime);
                                    } catch (InterruptedException e) {
                                        // Do nothing
                                    }
                                }

                            }

                        } catch (Throwable e) {
                            // catch any Exception or Error to kill the process,
                            // see bug 0003567
                            running = true;
                            ExceptionHandler.process(e);
                            addErrorMessage(e);
                            kill();
                        } finally {
                            progressMonitor.done();
                            // System.out.println("exitValue:" +
                            // ps.exitValue());
                        }
                    }
                });
            } catch (InvocationTargetException e1) {
                addErrorMessage(e1);
            } catch (InterruptedException e1) {
                addErrorMessage(e1);
            }

        } else {
            // See bug 0003567: When a prompt from context is cancelled or a
            // fatal error occurs during a job exec the
            // Kill button have to be pressed manually.
            this.running = true;
            setRunning(false);
        }
    }

    /**
     * DOC amaumont Comment method "getProcessor".
     * 
     * @return
     */
    protected IProcessor getProcessor(IProcess process, Property property) {
        // if (ComponentCategory.CATEGORY_4_DI.getName().equals(process.getComponentsType())) {
        // return new ExportModelJavaProcessor(process, property, true);
        // }
        return ProcessorUtilities.getProcessor(process, property);
    }

    public synchronized int kill() {
        return kill(null);
    }

    /**
     * Kill the process.
     * 
     * @return Exit code of the process.
     */
    public synchronized int kill(Integer returnExitValue) {
        int exitCode;

        if (!killing && isRunning()) {
            killing = true;
            if (returnExitValue != null) {
                ps = null;
            }
            try {
                exitCode = killProcess();
                if (startingMessageWritten) {
                    displayJobEndMessage(returnExitValue == null ? exitCode : returnExitValue);
                }
            } finally {
                killing = false;
            }
        } else {
            exitCode = 0;
        }

        setRunning(false);
        return exitCode;
    }

    /**
     * DOC yexiaowei Comment method "displayJobEndMessage".
     * 
     * @param exitCode
     */
    private void displayJobEndMessage(int exitCode) {

        final String endingPattern = Messages.getString("ProcessComposite.endPattern"); //$NON-NLS-1$
        MessageFormat mf = new MessageFormat(endingPattern);
        String byeMsg = mf.format(new Object[] { process.getLabel(), new Date(), new Integer(exitCode) });
        byeMsg = (processMessageManager.isLastMessageEndWithCR() ? "" : "\n") + byeMsg; //$NON-NLS-1$ //$NON-NLS-2$
        processMessageManager.addMessage(new ProcessMessage(MsgType.CORE_OUT, byeMsg));
    }

    private int killProcess() {
        if (psMonitor != null) {
            if (perfMonitor != null) {
                perfMonitor.stopThread();
                perfMonitor = null;
            }
            if (traceMonitor != null) {
                traceMonitor.stopThread();
                traceMonitor = null;
            }
            psMonitor.stopThread();
            psMonitor = null;
        }
        int exitCode = 0;
        if (ps != null) { // running process
            ps.destroy();
            try {
                exitCode = ps.exitValue();
            } catch (IllegalThreadStateException itse) {
                // Can be throw on some UNIX system :(
                // but the process is really killed.
            }
            ps = null;
        }
        if (debugProcess != null) {
            try {
                debugProcess.terminate();
            } catch (DebugException e) {
                // TODO Auto-generated catch block
                // e.printStackTrace();
                ExceptionHandler.process(e);
            }
        }
        return exitCode;
    }

    public void addErrorMessage(Throwable e) {
        StringBuffer message = new StringBuffer(STRING_LENGTH);
        message.append(Messages.getString("ProcessComposite.execFailed")); //$NON-NLS-1$
        message.append(e.getMessage());
        if (e.getCause() != null) {
            message.append("\n["); //$NON-NLS-1$
            message.append(e.getCause().getMessage());
            message.append("]"); //$NON-NLS-1$
        }
        message.append("\n"); //$NON-NLS-1$

        IProcessMessage processMsg = new ProcessMessage(MsgType.CORE_ERR, message.toString());
        processMessageManager.addMessage(processMsg);
    }

    private void findNewStatsPort() {
        statsPort = monitorPerf ? RunProcessPlugin.getDefault().getRunProcessContextManager().getPortForStatistics()
                : IProcessor.NO_STATISTICS;
    }

    public int getStatisticsPort() {
        return statsPort;
    }

    private void findNewTracesPort() {
        tracesPort = monitorTrace ? RunProcessPlugin.getDefault().getRunProcessContextManager().getPortForTraces()
                : IProcessor.NO_TRACES;
    }

    public int getTracesPort() {
        return tracesPort;
    }

    // private int getWatchPort() {
    // int port = watchAllowed ? RunProcessPlugin.getDefault()
    // .getRunProcessContextManager().getPortForWatch(this)
    // : Processor.WATCH_LIMITED;
    // return port;
    // }

    /**
     * Process activity monitor. <br/>
     * 
     * $Id$
     * 
     */
    protected class ProcessMonitor implements IProcessMonitor {

        volatile boolean stopThread;

        /** The monitoring process. */
        private final Process process;

        /** Input stream for stdout of the process. */
        private final BufferedReader outIs;

        /** Input stream for stderr of the process. */
        private final BufferedReader errIs;

        private boolean hasCompilationError = false;

        public ProcessMonitor(Process ps) {

            super();

            this.process = ps;
            this.outIs = new BufferedReader(new InputStreamReader(ps.getInputStream()));
            this.errIs = new BufferedReader(new InputStreamReader(ps.getErrorStream()));
        }

        /**
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            int exitValue = 0;
            while (!stopThread) {
                boolean dataPiped = extractMessages(false);
                processMessageManager.updateConsole();
                boolean ended;
                try {

                    if (!hasCompilationError) {
                        exitValue = process.exitValue();
                    }

                    // flush remaining messages
                    while (extractMessages(true) && !stopThread) {
                    }

                    // Read the end of the stream after the end of the process
                    ended = true;
                    stopThread = true;
                    try {
                        this.process.getInputStream().close();
                    } catch (IOException e) {
                        ExceptionHandler.process(e);
                    }

                    try {
                        this.process.getErrorStream().close();
                    } catch (IOException e) {
                        ExceptionHandler.process(e);
                    }

                } catch (IllegalThreadStateException itse) {
                    ended = false;
                } catch (Exception e) {
                    ended = false;
                }

                if (!dataPiped && !ended) {
                    synchronized (this) {
                        try {
                            final long waitTime = 5;
                            wait(waitTime);
                        } catch (InterruptedException e) {
                            // Do nothing
                        }
                    }
                }
            }
            kill(exitValue);
        }

        @Override
        public void stopThread() {
            stopThread = true;
            synchronized (this) {
                notify();
            }
        }

        private boolean extractMessages(boolean flush) {
            IProcessMessage messageOut = null;
            IProcessMessage messageErr = null;
            try {
                messageErr = extractMessage(errIs, MsgType.STD_ERR, flush);
                if (messageErr != null) {
                    if (messageErr.getContent().contains("Unresolved compilation problem")) { //$NON-NLS-1$
                        hasCompilationError = true;
                    }
                    processMessageManager.addMessage(messageErr);
                }
                messageOut = extractMessage(outIs, MsgType.STD_OUT, flush);
                if (messageOut != null) {
                    processMessageManager.addMessage(messageOut);
                }
            } catch (IOException ioe) {
                addErrorMessage(ioe);
                ExceptionHandler.process(ioe);
            }
            return messageOut != null || messageErr != null;
        }

        /**
         * Extract a message from a stream.
         * 
         * @param is Input stream to be read.
         * @param type Type of message read.
         * @param flush
         * @return the message extracted or null if no message was present.
         * @throws IOException Extraction failure.
         */
        private IProcessMessage extractMessage(final BufferedReader is, MsgType type, boolean flush) throws IOException {

            IProcessMessage msg;
            if (is.ready()) {

                StringBuilder sb = new StringBuilder();

                String data = null;
                long timeStart = System.currentTimeMillis();
                while (is.ready()) {
                    data = is.readLine();
                    if (data == null) {
                        break;
                    }
                    sb.append(data).append("\n"); //$NON-NLS-1$
                    if (sb.length() > 1024 || System.currentTimeMillis() - timeStart > 100) {
                        break;
                    }
                }
                msg = new ProcessMessage(type, sb.toString());
            } else {
                msg = null;
            }
            return msg;
        }

    }

    /**
     * DOC ycbai Comment method "isLastData".
     * 
     * @param reader
     * @param previousData
     * @return
     */
    private boolean isEndData(String line) {
        String temp = line.substring(line.indexOf("|") + 1);
        temp = temp.substring(temp.indexOf("|") + 1);
        temp = temp.substring(temp.indexOf("|") + 1);
        temp = temp.substring(temp.indexOf("|") + 1);
        temp = temp.substring(temp.indexOf("|") + 1);
        if (temp.startsWith("end job")) {
            return true;
        }

        return false;
    }

    /**
     * Performance monitor. <br/>
     * 
     * $Id$
     * 
     */
    private class PerformanceMonitor implements Runnable {

        private volatile boolean stopThread;

        private String lastData;

        private Set<IPerformance> performanceDataSet = new HashSet<IPerformance>();

        public PerformanceMonitor() {
            super();
        }

        /**
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            // final int acceptTimeout = 30000;

            // Waiting connection from process
            Socket processSocket = null;
            ServerSocket serverSock = null;
            do {
                try {
                    serverSock = new ServerSocket(getStatisticsPort());
                    processSocket = serverSock.accept();

                } catch (IOException e) {
                    ExceptionHandler.process(e);
                    stopThread |= !isRunning();
                } finally {
                    try {
                        if (serverSock != null) {
                            serverSock.close();
                            new FreePortFinder().removePort(statsPort);
                        }
                    } catch (IOException e1) {
                        // e1.printStackTrace();
                        ExceptionHandler.process(e1);
                    }
                }
            } while (processSocket == null && !stopThread);

            String preData = null;
            if (processSocket != null && !stopThread) {
                try {
                    InputStream in = processSocket.getInputStream();
                    LineNumberReader reader = new LineNumberReader(new InputStreamReader(in));
                    while (!stopThread) {
                        String line = reader.readLine();
                        showMapReduceData(line);
                        if (LanguageManager.getCurrentLanguage() == ECodeLanguage.JAVA) {
                            if (line != null) {
                                if (line.startsWith("0")) {
                                    if (isEndData(line)) {
                                        lastData = preData;
                                    }
                                    // 0 = job information
                                    // 1 = connection information
                                    continue;
                                }
                                String[] infos = line.split("\\|");
                                if (infos.length < 5 || !infos[1].equals(infos[2]) || !infos[1].equals(infos[3])) {
                                    // we only take actually informations for the main jobs, other informations won't be
                                    // used.
                                    continue;
                                }

                                // "0|GnqOsQ|GnqOsQ|GnqOsQ|iterate1|exec1" -->"iterate1|exec1"
                                if (line.trim().length() > 22) {
                                    String temp = line.substring(line.indexOf("|") + 1); // remove the 0|
                                    temp = temp.substring(temp.indexOf("|") + 1); // remove the first GnqOsQ|
                                    temp = temp.substring(temp.indexOf("|") + 1); // remove the second GnqOsQ|
                                    temp = temp.substring(temp.indexOf("|") + 1); // remove the third GnqOsQ|
                                    line = temp;
                                }
                            }
                        }
                        preData = line;
                        final String data = line;
                        // // for feature:11356
                        // if (data != null && data.split("\\|").length == 2) {
                        // continue;
                        // }
                        if (data == null) {
                            stopThread = true;
                        } else {
                            final PerformanceData perfData = new PerformanceData(data);
                            String connectionId = perfData.getConnectionId();
                            // handle connectionId as row1.1 and row1
                            connectionId = connectionId.split("\\.")[0]; //$NON-NLS-1$
                            final IConnection conn = traceConnectionsManager.finConnectionByUniqueName(connectionId);

                            processPerformances(data, perfData, conn);
                        }
                    }
                } catch (Exception e) {
                    // Do nothing : process is ended
                } finally {
                    try {
                        processSocket.close();
                    } catch (IOException ioe) {
                        // Do nothing
                    }
                }
            }
        }

        private void processPerformances(final String data, final PerformanceData perfData, final IConnection conn) {
            if (conn == null) {
                return;
            }
            processPerformanceForConnection(data, perfData, conn);
            String uniqueName = ConnectionUtil.getConnectionUnifiedName(conn);
            IConnection[] shadowConnections = traceConnectionsManager.getShadowConnenctions(uniqueName);
            if (shadowConnections != null) {
                for (IConnection shadowConn : shadowConnections) {
                    processPerformanceForConnection(data, perfData, shadowConn);
                }
            }
        }

        private void processPerformanceForConnection(final String data, final PerformanceData perfData, final IConnection conn) {
            if (conn != null && conn instanceof IPerformance) {
                final IPerformance performance = (IPerformance) conn;
                if (!performanceDataSet.contains(performance)) {
                    performance.resetStatus();
                }
                performanceDataSet.add(performance);

                Display.getDefault().asyncExec(new Runnable() {

                    @Override
                    public void run() {
                        if (data != null) {
                            if (perfData.isClearCommand()) {
                                performance.clearPerformanceDataOnUI();
                            } else {
                                performance.setPerformanceData(data);
                            }
                            // clear status when run to the last data.
                            if (data.equals(lastData)) {
                                for (IPerformance p : performanceDataSet) {
                                    p.resetStatus();
                                }
                            }
                        }
                    }
                });
            }
        }

        public void stopThread() {
            stopThread = true;
            synchronized (this) {
                notify();
            }
        }

        private INode findNode(final String nodeId) {
            INode node = null;
            for (Iterator<? extends INode> i = process.getGraphicalNodes().iterator(); node == null && i.hasNext();) {
                INode psNode = i.next();
                if (nodeId.equals(psNode.getUniqueName())) {
                    node = psNode;
                }
            }
            return node;
        }

    }

    /**
     * Trace monitor. <br/>
     * 
     * $Id$
     * 
     */
    private class TraceMonitor implements Runnable {

        private volatile boolean stopThread;

        private volatile boolean userow = false;

        int dataSize = 0;

        int readSize = 0;

        private List connectionSize = new ArrayList();

        public TraceMonitor() {
            super();
            isTracPause = false;
        }

        /**
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            // final int acceptTimeout = 30000;

            // Waiting connection from process
            Socket processSocket = null;
            ServerSocket serverSock = null;
            // used for trace of tmap
            final Map<IConnection, Map<String, TraceData>> connAndTraces = new HashMap<IConnection, Map<String, TraceData>>();
            do {
                try {
                    serverSock = new ServerSocket(getTracesPort());
                    // serverSock.setSoTimeout(acceptTimeout);
                    processSocket = serverSock.accept();
                } catch (IOException e) {
                    ExceptionHandler.process(e);
                    try {
                        if (serverSock != null) {
                            serverSock.close();
                        }
                    } catch (IOException e1) {
                        // e1.printStackTrace();
                        ExceptionHandler.process(e1);
                    } finally {
                        try {
                            if (serverSock != null) {
                                serverSock.close();
                            }
                        } catch (IOException e1) {
                            // e1.printStackTrace();
                            ExceptionHandler.process(e1);
                        }
                    }
                    stopThread |= !isRunning();
                }
            } while (processSocket == null && !stopThread);

            if (processSocket != null && !stopThread) {
                try {
                    InputStream in = processSocket.getInputStream();
                    NoHeaderObjectInputStream reader = new NoHeaderObjectInputStream(in);
                    setBasicRun(false);

                    boolean lastIsPrivious = false;
                    boolean lastRow = false;
                    final List<Map<String, TraceData>> connectionData = new ArrayList<Map<String, TraceData>>();
                    while (!stopThread) {
                        final Object data = reader.readObject();
                        NoHeaderObjectOutputStream pred = new NoHeaderObjectOutputStream(processSocket.getOutputStream());

                        // System.out.println(data);

                        if (data == null) {
                            stopThread = true;
                        } else if (TraceStatusBean.ID_STATUS.equals(data)) {
                            if (isTracPause()) {
                                pred.writeObject(TraceStatusBean.PAUSE);
                            } else if (lastIsRow) {
                                pred.writeObject(TraceStatusBean.NEXT_ROW);
                            } else {
                                // testing only
                                pred.writeObject(TraceStatusBean.NEXT_BREAKPOINT);
                            }

                            continue;
                        } else if (TraceStatusBean.UI_STATUS.equals(data)) {
                            // wait for UI here, for next click, then send STATUS_OK
                            if (isBasicRun()) {
                                pred.writeObject(TraceStatusBean.STATUS_OK);
                            }

                            if (!checkBreakpoint()) {
                                firePropertyChange(NEXTBREAKPOINT, true, false);
                            } else {
                                firePropertyChange(NEXTBREAKPOINT, false, true);
                            }

                            boolean enablePreviousRow = false;
                            if (dataSize - readSize != 0 && dataSize != connectionSize.size()) {
                                enablePreviousRow = true;
                            }
                            firePropertyChange(PREVIOUS_ROW, false, enablePreviousRow);
                            if (isNextPoint()) {

                                firePropertyChange(PREVIOUS_ROW, false, true);
                                pred.writeObject(TraceStatusBean.STATUS_OK);
                                setNextBreakPoint(false);
                                lastIsRow = false;
                            } else if (isNextRow()) {
                                firePropertyChange(PREVIOUS_ROW, false, true);
                                if (readSize > 0) {
                                    pred.writeObject(TraceStatusBean.STATUS_WAITING);
                                    if (lastIsPrivious) {
                                        readSize = readSize - connectionSize.size();
                                        lastIsPrivious = false;
                                    }
                                    for (int b = 0; b < connectionSize.size(); b++) {
                                        if ((dataSize - readSize < connectionData.size())) {
                                            if (readSize >= 0) {
                                                final Map<String, TraceData> nextRowTrace = connectionData.get(dataSize
                                                        - readSize);
                                                if (nextRowTrace != null) {
                                                    String connectionId = null;
                                                    for (String key : nextRowTrace.keySet()) {
                                                        if (!key.contains("[MAIN]") && !key.contains(":")) {
                                                            connectionId = key;
                                                        }
                                                    }
                                                    final IConnection connection = traceConnectionsManager
                                                            .finConnectionByUniqueName(connectionId);
                                                    if (connection != null) {
                                                        if (!connectionSize.contains(connection)) {
                                                            connectionSize.add(connection);
                                                        }
                                                        Display.getDefault().syncExec(new Runnable() {

                                                            @Override
                                                            public void run() {
                                                                setTraceData(connection, nextRowTrace);
                                                            }
                                                        });
                                                    }
                                                }
                                            }
                                            readSize = readSize - 1;
                                        }
                                    }
                                    if (readSize == 0) {
                                        lastIsPrivious = false;
                                    }
                                    lastRow = true;
                                } else {
                                    pred.writeObject(TraceStatusBean.STATUS_OK);
                                }
                                setNextRow(false);
                                lastIsRow = true;

                            } else if (isPriviousRow()) {
                                lastIsPrivious = true;
                                if (lastRow || readSize == 0) {
                                    readSize = readSize + connectionSize.size();
                                    lastRow = false;
                                }

                                for (int b = 0; b < connectionSize.size(); b++) {
                                    readSize = readSize + 1;
                                    if (dataSize - readSize >= 0) {
                                        final Map<String, TraceData> previousRowTrace = connectionData.get(dataSize - readSize);
                                        if (previousRowTrace != null) {
                                            String connectionId = null;
                                            for (String key : previousRowTrace.keySet()) {
                                                if (!key.contains("[MAIN]") && !key.contains(":")) {
                                                    connectionId = key;
                                                }
                                            }
                                            final IConnection connection = traceConnectionsManager
                                                    .finConnectionByUniqueName(connectionId);
                                            if (connection != null) {
                                                if (!connectionSize.contains(connection)) {
                                                    connectionSize.add(connection);
                                                }
                                                Display.getDefault().syncExec(new Runnable() {

                                                    @Override
                                                    public void run() {
                                                        setTraceData(connection, previousRowTrace);
                                                    }
                                                });
                                            }
                                            if (dataSize - readSize == 0) {
                                                firePropertyChange(PREVIOUS_ROW, true, false);
                                            }
                                        } else {
                                            readSize = dataSize;

                                        }
                                    }
                                }
                                pred.writeObject(TraceStatusBean.STATUS_WAITING);
                                setPreviousRow(false);
                            } else {
                                if (dataSize == connectionSize.size()) {
                                    firePropertyChange(PREVIOUS_ROW, true, false);
                                }
                                lastIsRow = false;
                                pred.writeObject(TraceStatusBean.STATUS_WAITING);
                            }
                            // firePropertyChange(BREAKPOINT_BAR, false, false);
                            continue;
                        } else if (data instanceof TraceDataBean && ((TraceDataBean) data).getData() != null) {
                            firePropertyChange(BREAKPOINT_BAR, true, false);
                            TraceDataBean traceDataBean = (TraceDataBean) data;
                            TraceData traceData = new TraceData();
                            traceData.setConnectionId(traceDataBean.getConnectionId());
                            traceData.setNbLine(traceDataBean.getNbLine());
                            traceData.setData(traceDataBean.getData());
                            final String idPart = traceData.getConnectionId();
                            String id = null;
                            boolean isMapTrace = false;
                            if (idPart != null) {
                                if (idPart.endsWith("[MAIN]")) {
                                    id = idPart.substring(0, idPart.indexOf("[MAIN]"));
                                    isMapTrace = true;
                                } else if (idPart.contains(":") && idPart.split(":").length == 2) {
                                    id = idPart.split(":")[0];
                                    isMapTrace = true;
                                } else {
                                    id = idPart;
                                }
                            }
                            final IConnection connection = traceConnectionsManager.finConnectionByUniqueName(id);
                            if (connection != null) {
                                if (!connectionSize.contains(connection)) {
                                    connectionSize.add(connection);
                                }
                                // int sepIndex1 = data.indexOf("|");
                                // int sepIndex2 = sepIndex1 != -1 ? data.indexOf("|", sepIndex1 + 1) : -1;
                                // if (sepIndex2 != -1) {
                                int lineCount = traceData.getNbLine();
                                if (lineCount == 1) {
                                    setBasicRun(false);
                                }
                                // }
                                Map<String, TraceData> traceMap = connAndTraces.get(connection);
                                if (traceMap == null) {
                                    traceMap = new HashMap<String, TraceData>();
                                    connAndTraces.put(connection, traceMap);
                                }
                                traceMap.put(idPart, traceData);
                                if (isMapTrace) {
                                    continue;
                                }

                                connectionData.add(traceMap);
                                dataSize++;
                                if (connectionData.size() > (connectionSize.size() * 6) - 1) {
                                    for (int i = 0; i < connectionSize.size(); i++) {
                                        connectionData.remove(0);
                                        dataSize = dataSize - 1;
                                    }
                                }
                                Display.getDefault().syncExec(new Runnable() {

                                    @Override
                                    public void run() {
                                        if (data != null) {
                                            Map<String, TraceData> curTraceData = connAndTraces.get(connection);
                                            setTraceData(connection, curTraceData);
                                            connAndTraces.clear();
                                        }
                                    }
                                });
                            }
                        }
                    }
                } catch (IOException e) {
                    // Do nothing : process is ended
                } catch (ClassNotFoundException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } finally {
                    try {
                        setBasicRun(false);
                        processSocket.close();
                    } catch (IOException ioe) {
                        // Do nothing
                    }
                }
            }
        }

        public void stopThread() {
            stopThread = true;
            synchronized (this) {
                notify();
            }
        }

        private void setTraceData(IConnection conn, Map<String, TraceData> curTraceData) {
            if (conn != null) {
                Map<String, TraceData> dataMap = new HashMap<String, TraceData>(curTraceData);
                conn.setTraceData(dataMap);
                String uniqueName = ConnectionUtil.getConnectionUnifiedName(conn);
                IConnection[] shadowConnections = traceConnectionsManager.getShadowConnenctions(uniqueName);
                if (shadowConnections != null) {
                    TraceData data = dataMap.get(uniqueName);
                    for (IConnection shadowConn : shadowConnections) {
                        // FIXME, because the connection name is front of data, and not used, so no need change the data
                        // for shadow connection.
                        dataMap.put(ConnectionUtil.getConnectionUnifiedName(shadowConn), data);
                        shadowConn.setTraceData(dataMap);
                    }
                }
            }
        }
    }

    /**
     * Getter for watchAllowed.
     * 
     * @return the watchAllowed
     */
    public boolean isWatchAllowed() {
        return this.watchAllowed;
    }

    /**
     * Sets the watchAllowed.
     * 
     * @param watchAllowed the watchAllowed to set
     */
    public void setWatchAllowed(boolean watchAllowed) {
        if (this.watchAllowed != watchAllowed) {
            this.watchAllowed = watchAllowed;
            firePropertyChange(PROR_SWITCH_TIME, Boolean.valueOf(!watchAllowed), Boolean.valueOf(watchAllowed));
        }
    }

    public String getLog4jLevel() {
        return log4jLevel;
    }

    public void setLog4jLevel(String log4jLevel) {
        this.log4jLevel = log4jLevel;
    }

    public boolean isUseCustomLevel() {
        return useCustomLevel;
    }

    public void setUseCustomLevel(boolean useCustomLevel) {
        this.useCustomLevel = useCustomLevel;
    }

    public ITargetExecutionConfig getSelectedTargetExecutionConfig() {
        return this.selectedTargetExecutionConfig;
    }

    public void setSelectedTargetExecutionConfig(ITargetExecutionConfig selectedTargetExecutionConfiguration) {
        this.selectedTargetExecutionConfig = selectedTargetExecutionConfiguration;
    }

    /**
     * DOC amaumont Comment method "createProcessMonitor".
     * 
     * @param process
     * @return
     */
    protected IProcessMonitor createProcessMonitor(Process process) {
        return new ProcessMonitor(process);
    }

    public void addDebugResultToConsole(IProcessMessage message) {
        processMessageManager.addDebugResultToConsole(message);

    }

    public void setDebugProcess(org.eclipse.debug.core.model.IProcess debugProcess) {
        this.debugProcess = debugProcess;
    }

    public org.eclipse.debug.core.model.IProcess getDebugProcess() {
        return this.debugProcess;
    }

    public void setSaveBeforeRun(boolean saveBeforeRun) {
        this.saveBeforeRun = saveBeforeRun;
    }

    /**
     * Getter for saveBeforeRun.
     * 
     * @return the saveBeforeRun
     */
    public boolean isSaveBeforeRun() {
        return this.saveBeforeRun;
    }

    public boolean isClearBeforeExec() {
        return this.clearBeforeExec;
    }

    public void setClearBeforeExec(boolean clearBeforeExec) {
        this.clearBeforeExec = clearBeforeExec;
    }

    /**
     * Getter for isTracPause.
     * 
     * @return the isTracPause
     */
    public boolean isTracPause() {
        return this.isTracPause;
    }

    /**
     * Sets the isTracPause.
     * 
     * @param isTracPause the isTracPause to set
     */
    public void setTracPause(boolean isTracPause) {
        this.isTracPause = isTracPause;
    }

    public Process getSystemProcess() {
        return ps;
    }

    // /**
    // * DOC xzhang Comment method "startPerformanceMonitor".
    // *
    // * For bug 5430, modifyed by xzhang
    // */
    // public void startPerformanceMonitor() {
    // final IProcessor processor = getProcessor(process);
    // IProgressService progressService = PlatformUI.getWorkbench().getProgressService();
    //
    // try {
    // progressService.run(false, true, new IRunnableWithProgress() {
    //
    // public void run(final IProgressMonitor monitor) {
    //
    // final EventLoopProgressMonitor progressMonitor = new EventLoopProgressMonitor(monitor);
    //
    //                    progressMonitor.beginTask(Messages.getString("ProcessComposite.buildTask"), IProgressMonitor.UNKNOWN); //$NON-NLS-1$
    // try {
    // testPort();
    // // findNewStatsPort();
    // final IContext context = getSelectedContext();
    // if (monitorPerf) {
    // clearThreads();
    // perfMonitor = new PerformanceMonitor();
    //                            new Thread(perfMonitor, "PerfMonitor_" + process.getLabel()).start(); //$NON-NLS-1$
    // perMonitorList.add(perfMonitor);
    // }
    // // findNewTracesPort();
    // if (monitorTrace) {
    // traceMonitor = new TraceMonitor();
    //                            new Thread(traceMonitor, "TraceMonitor_" + process.getLabel()).start(); //$NON-NLS-1$
    // }
    // } catch (Throwable e) {
    // ExceptionHandler.process(e);
    // addErrorMessage(e);
    // kill();
    // }
    // }
    // });
    // } catch (InvocationTargetException e1) {
    // addErrorMessage(e1);
    // } catch (InterruptedException e1) {
    // addErrorMessage(e1);
    // }
    // }

    private void clearThreads() {
        for (PerformanceMonitor perMonitor : perMonitorList) {
            perMonitor.stopThread();
            perMonitor = null;
        }
        perMonitorList.clear();
    }

    private String getLog4jRuntimeLevel() {
        boolean log4jActivate = Log4jPrefsSettingManager.getInstance().isLog4jEnable();
        String level = "";
        if (log4jActivate) {
            level = getLog4jLevel();
            if (!isUseCustomLevel()) {
                level = null;
            } else {
                if (level != null) {
                    level = LOG4J_LEVEL_ARG + (level.equals("") ? LOG4J_DEFAULT_LEVEL : level.toLowerCase());
                }
            }
        } else {
            level = null;
        }
        return level;
    }

    /**
     * DOC Administrator Comment method "setNextBreakPoint".
     */
    public void setNextBreakPoint(Boolean nextBreakpoint) {
        // TODO Auto-generated method stub
        this.nextBreakpoint = nextBreakpoint;
    }

    /**
     * DOC Administrator Comment method "isNextPoint".
     * 
     * @return
     */
    private boolean isNextPoint() {
        // TODO Auto-generated method stub
        return nextBreakpoint;
    }

    /**
     * DOC Administrator Comment method "setNextRow".
     * 
     * @param b
     */
    public void setNextRow(boolean b) {
        // TODO Auto-generated method stub
        this.nextRow = b;
    }

    public boolean isNextRow() {
        return this.nextRow;
    }

    /**
     * DOC Administrator Comment method "setPreviousRow".
     * 
     * @param b
     */
    public void setPreviousRow(boolean b) {
        // TODO Auto-generated method stub
        this.priviousRow = b;
    }

    public boolean isPriviousRow() {
        return this.priviousRow;
    }

    public void setLastIsRow(boolean lastIsRow) {
        this.lastIsRow = lastIsRow;
    }

    public boolean isSelectAllTrace() {
        return this.selectAllTrace;
    }

    public void setSelectAllTrace(boolean selectAllTrace) {
        this.selectAllTrace = selectAllTrace;
    }

    public boolean isBasicRun() {
        return this.isBasicRun;
    }

    public void setBasicRun(boolean isBasicRun) {
        this.isBasicRun = isBasicRun;
    }

    private void showMapReduceData(String data) {
        if (!getProcess().getComponentsType().equals("MR")) {
            return;
        }
        if (!data.contains("|")) {
            return;
        }
        String[] datas = data.split("\\|");
        if (datas.length < 4) {
            return;
        }
        final Integer groupID = new Integer(datas[0]);
        //        if ((!"".equals(datas[0])) && datas[0] != null) { //$NON-NLS-1$
        // groupID = Integer.parseInt(datas[0]);
        // }
        final String mrName = datas[1];
        final Double percentMap = new Double(datas[2]);
        //        if ((!"".equals(datas[2])) && datas[2] != null) { //$NON-NLS-1$  
        // percentMap = Double.parseDouble(datas[2]);
        // }
        final Double percentReduce = new Double(datas[3]);
        //        if ((!"".equals(datas[3])) && datas[3] != null) { //$NON-NLS-1$  
        // percentMap = Double.parseDouble(datas[3]);
        // }

        Display.getDefault().asyncExec(new Runnable() {

            @Override
            public void run() {
                List<? extends INode> nodeList = process.getGraphicalNodes();
                for (INode node : nodeList) {
                    if ((node instanceof Node) && ((Node) node).getMrGroupId() != null
                            && ((Node) node).getMrGroupId().equals(groupID)) {
                        if (((Node) node).getNodeContainer() instanceof JobletContainer) {
                            ((JobletContainer) ((Node) node).getNodeContainer()).setMrName(mrName);
                            if (((Node) node).isMapReduceStart()) {
                                ((JobletContainer) ((Node) node).getNodeContainer()).updateState(
                                        "UPDATE_STATUS", mrName, percentMap, percentReduce); //$NON-NLS-1$
                            }
                        }
                    }
                }
            }
        });
    }
}
