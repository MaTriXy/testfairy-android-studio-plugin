package com.testfairy.plugin.intellij;

import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.ArrayUtil;
import com.testfairy.plugin.intellij.exception.AndroidModuleBuildFileNotFoundException;
import com.testfairy.plugin.intellij.exception.TestFairyException;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import static com.testfairy.plugin.intellij.Util.*;

public class BuildAndSendToTestFairy extends AnAction {

	private Project project;
	private List<String> testFairyTasks;
	private ConfigureTestFairy configureTestFairyAction;

	@Override
	public void actionPerformed(AnActionEvent e) {
		this.project = e.getProject();
		Plugin.setProject(project);

		activateToolWindows();

		ToolWindowManager.getInstance(project).getToolWindow("TestFairy").activate(new Runnable() {
			@Override
			public void run() {
				try {

					TestFairyConsole.clear();

					configureTestFairyAction = new ConfigureTestFairy();

					if (!configureTestFairyAction.isBuildFilePatched(project)) {
						configureTestFairyAction.execute(project);
					}

					if (!configureTestFairyAction.isBuildFilePatched(project)) {
						Plugin.broadcastError("TestFairy is not configured for this project.");
						return;
					}

					execute(project);

				} catch (AndroidModuleBuildFileNotFoundException e1) {
					Plugin.broadcastError(e1.getMessage());
				} catch (Exception exception) {
					Plugin.logException(exception);
				}
			}
		});
	}

	private void activateToolWindows() {
		if (ToolWindowManager.getInstance(project).getToolWindow("Messages") != null) {
			ToolWindowManager.getInstance(project).getToolWindow("Messages").activate(null);
		}
		ToolWindowManager.getInstance(project).getToolWindow("Event Log").activate(null);
	}

	private void execute(final Project project) throws AndroidModuleBuildFileNotFoundException {
		Task.Backgroundable bgTask = new Task.Backgroundable(project, "Uploading to TestFairy", false) {
			public int selection;

			public int getSelection() {
				return this.selection;
			}

			@Override
			public void run(ProgressIndicator indicator) {
				Plugin.setIndicator(indicator);
				indicator.setIndeterminate(true);

				Plugin.logInfo("Preparing Gradle Wrapper");
				testFairyTasks = getTestFairyTasks();
				Plugin.setIndicator(null);
			}

			@Override
			public void onSuccess() {
				if (testFairyTasks.size() == 0) {
					Plugin.broadcastError("No TestFairy build tasks found.");
					return;
				}

				selection = Messages.showChooseDialog(
					"Select what you want to do",
						"TestFairy",
						getTestFairyTaskExplanations(),
						testFairyTasks.get(0),
						Icons.TESTFAIRY_ICON
				);

				if (selection == -1) {
					return;
				}

				new Backgroundable(project, "Uploading to TestFairy", false) {

					private boolean shouldLaunchBrowser = false;
					private final DialogWrapper.DoNotAskOption doNotAskOption = new DoNotAskBrowserOption();
					private final Runnable showBrowserDialog = new Runnable() {
						@Override
						public void run() {
							shouldLaunchBrowser = Plugin.shouldLaunchBrowser() == null ? Messages.showOkCancelDialog(
									"Would you like to preview your release in your browser?",
									"Preview",
									"Go to TestFairy",
									"No thanks",
									Icons.TESTFAIRY_ICON,
									doNotAskOption
							) == Messages.OK : Plugin.shouldLaunchBrowser();
						}
					};

					@Override
					public void run(ProgressIndicator indicator) {
						try {
							Plugin.setIndicator(indicator);
							indicator.setIndeterminate(true);

							String url = packageRelease(testFairyTasks.get(selection));
							ApplicationManager.getApplication().invokeAndWait(showBrowserDialog, ModalityState.defaultModalityState());
							if (shouldLaunchBrowser) {
								launchBrowser(url);
							}

							Plugin.logInfo("Done");
							Thread.sleep(3000);
							indicator.stop();
							Plugin.setIndicator(null);
						} catch (InterruptedException e1) {
							Plugin.logException(e1);
						} catch (TestFairyException tfe) {
							Plugin.broadcastError("Invalid TestFairy API key. Please use Tools/TestFairy/Settings to fix.");
						} catch (URISyntaxException e) {
							Plugin.logException(e);
						}
					}
				}.queue();
			}

		};
		bgTask.queue();
	}

	private List<String> getTestFairyTasks() {
		List<String> tasks = new ArrayList<String>();

		OutputStream outputStream = new OutputStream() {
			private StringBuilder string = new StringBuilder();

			@Override
			public void write(int b) throws IOException {
				this.string.append((char) b);
			}

			//Netbeans IDE automatically overrides this toString()
			public String toString() {
				return this.string.toString();
			}
		};

		ProjectConnection connection = GradleConnector.newConnector()
				.forProjectDirectory(getProjectDirectoryFile())
				.connect();

		BuildLauncher buildLauncher = connection.newBuild();
		buildLauncher.forTasks(":tasks");

		setStandardOutputOfBuildLauncher(buildLauncher, outputStream);
		runBuildLauncher(buildLauncher);

		for (String line : outputStream.toString().split("\\r?\\n")) {
			if (line.startsWith("testfairy")) {
				tasks.add(line.split(" ")[0]);
			}
		}

		// TODO : sort these task in a user friendly way

		return tasks;
	}

	private String packageRelease(String task) throws TestFairyException {
		String buildUrl = "";
		OutputStream outputStream;
		try {
			outputStream = new OutputStream() {
				private StringBuilder string = new StringBuilder();

				@Override
				public void write(int b) throws IOException {
					char[] s = {(char) b};
					this.string.append((char) b);
					TestFairyConsole.consoleView.print(new String(s), ConsoleViewContentType.SYSTEM_OUTPUT);
				}

				//Netbeans IDE automatically overrides this toString()
				public String toString() {
					return this.string.toString();
				}
			};

			ProjectConnection connection;
			connection = GradleConnector.newConnector()
				.forProjectDirectory(getProjectDirectoryFile())
				.connect();

			BuildLauncher build = connection.newBuild();

			withArgumentsBuildLauncher(build.forTasks(task), "-Pinstrumentation=off",
				"-PtestfairyUploadedBy=TestFairy Android Studio Integration Plugin v" +
					PluginManager.getPlugin(PluginManager.getPluginByClassName("com.testfairy.plugin.intellij.Plugin")).getVersion());

			setStandardOutputOfBuildLauncher(build, outputStream);
			setStandardErrorOfBuildLauncher(build, outputStream);

			try {
				runBuildLauncher(build);
			} catch (GradleConnectionException gce) {
				if (checkInvalidAPIKey(gce)) {
					throw new TestFairyException("Invalid API key. Please use Tools/TestFairy/Settings to fix.");
				}
			} catch (IllegalStateException ise) {
				throw new TestFairyException(ise.getMessage());

			}

			connection.close();

			buildUrl = getBuildUrlFromGradleOutput(outputStream);

			Thread.sleep(3000);
		} catch (InterruptedException e1) {
			Plugin.logException(e1);
		}
		return buildUrl;
	}

	@NotNull
	private String getBuildUrlFromGradleOutput(OutputStream outputStream) {
		String result = "";
		String lines[] = outputStream.toString().split("\\r?\\n");
		int i = lines.length;
		while (--i >= 0) {
			if (lines[i].startsWith("http") && lines[i].contains(".testfairy.")) {
				result = lines[i];
				break;
			}
		}
		if (result.length() == 0) {
			Plugin.logError("TestFairy project URL not found in build output");
		}
		return result;
	}

	private boolean checkInvalidAPIKey(GradleConnectionException gce) {
		String stackTrace = Util.getStackTrace(gce);

		if (stackTrace.contains("Invalid API key")) {
			return true;
		}

		return false;
	}

	private File getProjectDirectoryFile() {
		return new File(project.getBasePath());
	}

	private void launchBrowser(String url) throws InterruptedException, URISyntaxException {
		if (url.length() < 5) return;
		Plugin.logInfo("Launching Browser: " + url);
		BrowserLauncher.getInstance().browse(new URI(url));
		Thread.sleep(3000);
	}

	private String[] getTestFairyTaskExplanations() {
		List<String> explanations = new ArrayList<String>();

		for (String task : testFairyTasks) {
			if (!task.startsWith("testfairyNdk")) {
				explanations.add("Build '" + task.replaceFirst("testfairy", "") + "' variant and send APK to TestFairy");
			} else {
				explanations.add("Send '" + task.replaceFirst("testfairyNdk", "") + "' symbols to TestFairy to symbolicate native crashes");
			}
		}

		return ArrayUtil.toStringArray(explanations);
	}

}
