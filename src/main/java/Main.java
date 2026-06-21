import java.util.Scanner;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        File workingDir = new File(System.getProperty("user.dir"));
        List<BackgroundTask> tasks = new ArrayList<>();

        while (true) {
            // print any finished background jobs before showing the prompt again, like bash
            // does
            JobManager.reapFinishedTasks(tasks);
            System.out.print("$ ");

            String line = scanner.nextLine();
            if (line.isEmpty()) {
                continue;
            }

            List<String> tokens = CommandParser.parseCommand(line);
            if (tokens.isEmpty()) {
                continue;
            }

            // pipelines get special-cased and delegated straight to a real shell, see
            // PipelineRunner
            if (tokens.contains("|")) {
                try {
                    PipelineRunner.runPipeline(tokens, workingDir);
                    continue;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            String outFile = null;
            String errFile = null;
            boolean appendOut = false;
            boolean appendErr = false;
            boolean runInBackground = false;

            // scan through tokens pulling out redirection operators (>, >>, 2>, 2>>) and
            // the "&" suffix,
            // leaving just the actual command + args behind in cmdTokens
            List<String> cmdTokens = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);

                if (token.equals(">") || token.equals("1>")) {
                    if (i + 1 < tokens.size()) {
                        outFile = tokens.get(i + 1);
                        appendOut = false;
                    }
                    i++;
                    continue;
                }

                if (token.equals(">>") || token.equals("1>>")) {
                    if (i + 1 < tokens.size()) {
                        outFile = tokens.get(i + 1);
                        appendOut = true;
                    }
                    i++;
                    continue;
                }

                if (token.equals("2>")) {
                    if (i + 1 < tokens.size()) {
                        errFile = tokens.get(i + 1);
                        appendErr = false;
                    }
                    i++;
                    continue;
                }

                if (token.equals("2>>")) {
                    if (i + 1 < tokens.size()) {
                        errFile = tokens.get(i + 1);
                        appendErr = true;
                    }
                    i++;
                    continue;
                }

                cmdTokens.add(token);
            }

            // trailing "&" means run it in the background instead of waiting on it
            if (!cmdTokens.isEmpty() && cmdTokens.get(cmdTokens.size() - 1).equals("&")) {
                runInBackground = true;
                cmdTokens.remove(cmdTokens.size() - 1);
            }

            tokens = cmdTokens;

            // if stderr is being redirected, touch/truncate (or prep for append on) the
            // file up front
            // so it exists even if the command never actually writes to stderr
            if (errFile != null) {
                try {
                    File errFileHandle = new File(errFile);
                    File parentDir = errFileHandle.getParentFile();
                    if (parentDir != null) {
                        parentDir.mkdirs();
                    }
                    try (PrintWriter writer = new PrintWriter(new FileWriter(errFileHandle, appendErr))) {
                        writer.flush();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (tokens.isEmpty()) {
                continue;
            }

            String command = tokens.get(0);

            if (command.equals("exit")) {
                break;
            } else if (command.equals("echo")) {
                StringBuilder output = new StringBuilder();
                for (int i = 1; i < tokens.size(); i++) {
                    if (i > 1) {
                        output.append(" ");
                    }
                    output.append(tokens.get(i));
                }

                if (outFile != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(outFile, appendOut))) {
                        writer.println(output.toString());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(output.toString());
                }
            } else if (command.equals("pwd")) {
                if (outFile != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(outFile, appendOut))) {
                        writer.println(workingDir.getAbsolutePath());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(workingDir.getAbsolutePath());
                }
            } else if (command.equals("cd")) {
                if (tokens.size() < 2) {
                    continue;
                }
                try {
                    File target;
                    String path = tokens.get(1);
                    if (path.equals("~")) {
                        target = new File(System.getenv("HOME"));
                    } else if (new File(path).isAbsolute()) {
                        target = new File(path);
                    } else {
                        target = new File(workingDir, path);
                    }
                    // resolve any ".." / "." segments and confirm it's a real directory before
                    // switching
                    target = target.getCanonicalFile();
                    if (target.exists() && target.isDirectory()) {
                        workingDir = target;
                    } else {
                        System.out.println("cd: " + path + ": No such file or directory");
                    }
                } catch (Exception e) {
                    System.out.println("cd: " + tokens.get(1) + ": No such file or directory");
                }
            } else if (command.equals("jobs")) {
                // same listing logic as the prompt-time reaper, just triggered manually by the
                // user typing "jobs"
                JobManager.printJobs(tasks);
                continue;
            } else if (command.equals("type")) {
                if (tokens.size() < 2) {
                    continue;
                }
                String target = tokens.get(1);
                String resultText;

                if (target.equals("echo") || target.equals("exit") || target.equals("type") || target.equals("pwd")
                        || target.equals("cd") || target.equals("jobs")) {
                    resultText = target + " is a shell builtin";
                } else {
                    String resolvedPath = PathResolver.locateExecutable(target);
                    if (resolvedPath != null) {
                        resultText = target + " is " + resolvedPath;
                    } else {
                        resultText = target + ": not found";
                    }
                }

                if (outFile != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(outFile, appendOut))) {
                        writer.println(resultText);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(resultText);
                }
            } else {
                // not a builtin - see if it's something on PATH we can actually exec
                String resolvedPath = PathResolver.locateExecutable(command);
                if (resolvedPath != null) {
                    try {
                        ProcessBuilder builder = new ProcessBuilder(tokens);
                        builder.directory(workingDir);

                        if (outFile != null) {
                            if (appendOut) {
                                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outFile)));
                            } else {
                                builder.redirectOutput(new File(outFile));
                            }
                        }

                        if (errFile != null) {
                            if (appendErr) {
                                builder.redirectError(ProcessBuilder.Redirect.appendTo(new File(errFile)));
                            } else {
                                builder.redirectError(new File(errFile));
                            }
                        }

                        // only inherit the streams that *aren't* already being redirected to a file
                        if (outFile == null && errFile == null) {
                            builder.inheritIO();
                        } else if (outFile == null) {
                            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        } else if (errFile == null) {
                            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process proc = builder.start();
                        if (runInBackground) {
                            // don't wait for it - just register it as a job and print its pid, then move on
                            int taskNumber = JobManager.getNextTaskNumber(tasks);
                            tasks.add(new BackgroundTask(taskNumber, proc.pid(), String.join(" ", tokens) + " &",
                                    "Running", proc));
                            System.out.println("[" + taskNumber + "] " + proc.pid());
                        } else {
                            proc.waitFor();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(line + ": command not found");
                }
            }
        }
    }
}

/**
 * Simple holder for a background job - basically what you'd see from `jobs` in
 * bash.
 */
class BackgroundTask {
    int taskNumber;
    long pid;
    String commandLine;
    String state; // "Running" or "Done"
    Process proc;

    BackgroundTask(int taskNumber, long pid, String commandLine, String state, Process proc) {
        this.taskNumber = taskNumber;
        this.pid = pid;
        this.commandLine = commandLine;
        this.state = state;
        this.proc = proc;
    }
}

class JobManager {
    private static boolean hasExited(Process proc) {
        if (!proc.isAlive()) {
            return true;
        }
        try {
            return proc.waitFor(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return !proc.isAlive();
        }
    }

    // find the smallest job number not currently in use, like bash does ([1], [2],
    // [3]...)
    static int getNextTaskNumber(List<BackgroundTask> tasks) {
        int candidate = 1;

        while (true) {
            boolean taken = false;

            for (BackgroundTask task : tasks) {
                if (task.taskNumber == candidate) {
                    taken = true;
                    break;
                }
            }

            if (!taken) {
                return candidate;
            }

            candidate++;
        }
    }

    // checks all background jobs, prints a "Done" line for any that finished, then
    // removes them
    static void reapFinishedTasks(List<BackgroundTask> tasks) {
        List<BackgroundTask> finished = new ArrayList<>();
        // snapshot so we don't get tripped up mutating the list while we're iterating
        List<BackgroundTask> snapshot = new ArrayList<>(tasks);

        for (int i = 0; i < snapshot.size(); i++) {
            BackgroundTask task = snapshot.get(i);

            if (hasExited(task.proc)) {
                task.state = "Done";
            }

            if (task.state.equals("Done")) {
                // strip the trailing "&" before printing, looks weird otherwise
                String displayCommand = task.commandLine.replaceAll("\\s*&\\s*$", "");
                String marker = " ";

                // bash marks the most recently added job with "+" and the one before it with
                // "-"
                if (i == snapshot.size() - 1) {
                    marker = "+";
                } else if (i == snapshot.size() - 2) {
                    marker = "-";
                }

                System.out.printf("[%d]%s  %-24s%s%n", task.taskNumber, marker, task.state, displayCommand);
                finished.add(task);
            }
        }

        tasks.removeAll(finished);
    }

    // builds the listing for ALL current jobs (running and done), as printed by the
    // `jobs` command;
    // removes any that are done while we're at it. Used both for direct printing
    // and for the
    // builtin-in-a-pipeline case where we need the output as a string.
    static String listJobs(List<BackgroundTask> tasks) {
        StringBuilder result = new StringBuilder();
        List<BackgroundTask> snapshot = new ArrayList<>(tasks);
        List<BackgroundTask> finished = new ArrayList<>();

        for (int i = 0; i < snapshot.size(); i++) {
            BackgroundTask task = snapshot.get(i);
            if (hasExited(task.proc)) {
                task.state = "Done";
            }

            String displayCommand = task.commandLine;
            if (task.state.equals("Done")) {
                displayCommand = displayCommand.replaceAll("\\s*&\\s*$", "");
            }

            String marker = " ";
            if (i == snapshot.size() - 1) {
                marker = "+";
            } else if (i == snapshot.size() - 2) {
                marker = "-";
            }

            result.append(String.format("[%d]%s  %-24s%s%n", task.taskNumber, marker, task.state, displayCommand));
            if (task.state.equals("Done")) {
                finished.add(task);
            }
        }

        tasks.removeAll(finished);
        return result.toString();
    }

    // prints the result of listJobs() directly to stdout, used by the `jobs`
    // builtin
    // when run as a standalone (non-pipeline) command
    static void printJobs(List<BackgroundTask> tasks) {
        System.out.print(listJobs(tasks));
    }
}

/**
 * Hand-written tokenizer that understands single quotes, double quotes, and
 * backslash escapes
 * (didn't want to pull in a full shell-parsing library just for this).
 */
class CommandParser {

    static List<String> parseCommand(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            // backslash outside quotes escapes the very next character literally
            if (!inSingle && !inDouble && ch == '\\') {
                if (i + 1 < line.length()) {
                    buf.append(line.charAt(i + 1));
                    i++;
                }
            } else if (inDouble && ch == '\\') {
                // inside double quotes, backslash only escapes " and \ - everything else stays
                // literal
                if (i + 1 < line.length()) {
                    char next = line.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        buf.append(next);
                        i++;
                    } else {
                        buf.append('\\');
                    }
                } else {
                    buf.append('\\');
                }
            } else if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (Character.isWhitespace(ch) && !inSingle && !inDouble) {
                // whitespace outside quotes ends the current token
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
            } else {
                buf.append(ch);
            }
        }

        // don't forget whatever's left in the buffer after the loop ends
        if (buf.length() > 0) {
            tokens.add(buf.toString());
        }

        return tokens;
    }
}

/**
 * Walks PATH looking for a matching, executable file - basically what `which`
 * does.
 */
class PathResolver {

    static String locateExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }

        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String dir : dirs) {
            File candidate = new File(dir, name);
            if (candidate.exists() && candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }

        return null;
    }
}

class Builtins {

    // keeping this as static so a builtin running "inside" a pipeline can
    // see/update
    // the shell's current directory without us having to pass it around everywhere
    private static File workingDirHolder;

    static boolean isBuiltinCommand(String name) {
        return name.equals("echo")
                || name.equals("type")
                || name.equals("pwd")
                || name.equals("cd")
                || name.equals("jobs")
                || name.equals("exit");
    }

    // runs a builtin and returns its output as a string instead of printing
    // directly
    static String runBuiltinForPipeline(List<String> parts, File workingDir, List<BackgroundTask> tasks) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }

        workingDirHolder = workingDir;

        String name = parts.get(0);
        StringBuilder result = new StringBuilder();

        if (name.equals("echo")) {
            for (int i = 1; i < parts.size(); i++) {
                if (i > 1) {
                    result.append(' ');
                }
                result.append(parts.get(i));
            }
            result.append('\n');
            return result.toString();
        }

        if (name.equals("pwd")) {
            return workingDirHolder.getAbsolutePath() + "\n";
        }

        if (name.equals("cd")) {
            if (parts.size() < 2) {
                return "";
            }

            String path = parts.get(1);
            File target;
            try {
                if (path.equals("~")) {
                    target = new File(System.getenv("HOME"));
                } else if (new File(path).isAbsolute()) {
                    target = new File(path);
                } else {
                    target = new File(workingDirHolder, path);
                }

                // canonicalize so ".." and "." resolve properly, and so we catch bad paths
                // early
                target = target.getCanonicalFile();
                if (target.exists() && target.isDirectory()) {
                    workingDirHolder = target;
                    return "";
                }

                return "cd: " + path + ": No such file or directory\n";
            } catch (Exception e) {
                return "cd: " + parts.get(1) + ": No such file or directory\n";
            }
        }

        if (name.equals("jobs")) {
            return JobManager.listJobs(tasks);
        }

        if (name.equals("type")) {
            if (parts.size() < 2) {
                return "";
            }

            String target = parts.get(1);
            if (target.equals("echo") || target.equals("exit") || target.equals("type")
                    || target.equals("pwd") || target.equals("cd") || target.equals("jobs")) {
                return target + " is a shell builtin\n";
            }

            String resolvedPath = PathResolver.locateExecutable(target);
            if (resolvedPath != null) {
                return target + " is " + resolvedPath + "\n";
            }
            return target + ": not found\n";
        }

        // only "exit" falls through to here since it's the last builtin we recognize
        return "exit is a shell builtin\n";
    }

    // exposes the (possibly updated) working dir after a pipeline-builtin call,
    // e.g. for `cd`
    static File getWorkingDirHolder() {
        return workingDirHolder;
    }
}

class PipelineRunner {

    static void runPipeline(List<String> parts, File workingDir) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", String.join(" ", parts));
        builder.directory(workingDir);
        builder.inheritIO();
        Process proc = builder.start();
        proc.waitFor();
    }
}