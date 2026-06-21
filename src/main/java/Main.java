import java.util.Scanner;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private static File currentDirectoryRef;
    private static List<Job> jobsRef;

    static class Job {
        int jobNumber;
        long pid;
        String command;
        String status;
        Process process;

        Job(int jobNumber, long pid, String command, String status, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.status = status;
            this.process = process;
        }
    }

    private static void pipeStreams(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;

        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush();
        }

        
    }

    private static int getNextJobNumber(List<Job> jobs) {
        int jobNumber = 1;

        while (true) {
            boolean used = false;

            for (Job job : jobs) {
                if (job.jobNumber == jobNumber) {
                    used = true;
                    break;
                }
            }

            if (!used) {
                return jobNumber;
            }

            jobNumber++;
        }
    }

    private static void reapJobs(List<Job> jobs) {
        List<Job> completedJobs = new ArrayList<>();
        List<Job> snapshot = new ArrayList<>(jobs);

        for (int i = 0; i < snapshot.size(); i++) {
            Job job = snapshot.get(i);

            if (!job.process.isAlive()) {
                job.status = "Done";
            }

            if (job.status.equals("Done")) {
                String commandText = job.command.replaceAll("\\s*&\\s*$", "");
                String marker = " ";

                if (i == snapshot.size() - 1) {
                    marker = "+";
                } else if (i == snapshot.size() - 2) {
                    marker = "-";
                }

                System.out.printf("[%d]%s  %-24s%s%n", job.jobNumber, marker, job.status, commandText);
                completedJobs.add(job);
            }
        }

        jobs.removeAll(completedJobs);
    }

    private static boolean isBuiltin(String command) {
        return command.equals("echo")
                || command.equals("type")
                || command.equals("pwd")
                || command.equals("cd")
                || command.equals("jobs")
                || command.equals("exit");
    }

    private static void runPipeline(List<String> tokens, File currentDirectory) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", String.join(" ", tokens));
        pb.directory(currentDirectory);
        pb.inheritIO();
        Process process = pb.start();
        process.waitFor();
    }

    private static String executeBuiltinForPipeline(List<String> tokens, File currentDirectory) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }

        currentDirectoryRef = currentDirectory;

        String command = tokens.get(0);
        StringBuilder output = new StringBuilder();

        if (command.equals("echo")) {
            for (int i = 1; i < tokens.size(); i++) {
                if (i > 1) {
                    output.append(' ');
                }
                output.append(tokens.get(i));
            }
            output.append('\n');
            return output.toString();
        }

        if (command.equals("pwd")) {
            return currentDirectoryRef.getAbsolutePath() + "\n";
        }

        if (command.equals("cd")) {
            if (tokens.size() < 2) {
                return "";
            }

            String path = tokens.get(1);
            File targetDir;
            try {
                if (path.equals("~")) {
                    targetDir = new File(System.getenv("HOME"));
                } else if (new File(path).isAbsolute()) {
                    targetDir = new File(path);
                } else {
                    targetDir = new File(currentDirectoryRef, path);
                }

                targetDir = targetDir.getCanonicalFile();
                if (targetDir.exists() && targetDir.isDirectory()) {
                    currentDirectoryRef = targetDir;
                    return "";
                }

                return "cd: " + path + ": No such file or directory\n";
            } catch (Exception e) {
                return "cd: " + tokens.get(1) + ": No such file or directory\n";
            }
        }

        if (command.equals("jobs")) {
            List<Job> snapshot = new ArrayList<>(jobsRef);
            List<Job> completedJobs = new ArrayList<>();

            for (int i = 0; i < snapshot.size(); i++) {
                Job job = snapshot.get(i);
                if (!job.process.isAlive()) {
                    job.status = "Done";
                }

                String commandText = job.command;
                if (job.status.equals("Done")) {
                    commandText = commandText.replaceAll("\\s*&\\s*$", "");
                }

                String marker = " ";
                if (i == snapshot.size() - 1) {
                    marker = "+";
                } else if (i == snapshot.size() - 2) {
                    marker = "-";
                }

                output.append(String.format("[%d]%s  %-24s%s%n", job.jobNumber, marker, job.status, commandText));
                if (job.status.equals("Done")) {
                    completedJobs.add(job);
                }
            }

            jobsRef.removeAll(completedJobs);
            return output.toString();
        }

        if (command.equals("type")) {
            if (tokens.size() < 2) {
                return "";
            }

            String target = tokens.get(1);
            if (target.equals("echo") || target.equals("exit") || target.equals("type")
                    || target.equals("pwd") || target.equals("cd") || target.equals("jobs")) {
                return target + " is a shell builtin\n";
            }

            String executablePath = findExecutable(target);
            if (executablePath != null) {
                return target + " is " + executablePath + "\n";
            }
            return target + ": not found\n";
        }

        return "exit is a shell builtin\n";
    }

    public static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }

        String[] directories = pathEnv.split(File.pathSeparator);
        for (String dir : directories) {
            File file = new File(dir, command);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    public static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (!inSingleQuote && !inDoubleQuote && c == '\\') {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            } else if (inDoubleQuote && c == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                    }
                } else {
                    current.append('\\');
                }
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        File currentDirectory = new File(System.getProperty("user.dir"));
        List<Job> jobs = new ArrayList<>();

        while (true) {
            reapJobs(jobs);
            System.out.print("$ ");

            String input = scanner.nextLine();
            if (input.isEmpty()) {
                continue;
            }

            List<String> tokens = parseCommand(input);
            if (tokens.isEmpty()) {
                continue;
            }

            currentDirectoryRef = currentDirectory;
            jobsRef = jobs;

            if (tokens.contains("|")) {
                try {
                    runPipeline(tokens, currentDirectory);
                    continue;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            String stdoutRedirect = null;
            String stderrRedirect = null;
            boolean appendStdout = false;
            boolean appendStderr = false;
            boolean isBackgroundJob = false;

            List<String> commandTokens = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);

                if (token.equals(">") || token.equals("1>")) {
                    if (i + 1 < tokens.size()) {
                        stdoutRedirect = tokens.get(i + 1);
                        appendStdout = false;
                    }
                    i++;
                    continue;
                }

                if (token.equals(">>") || token.equals("1>>")) {
                    if (i + 1 < tokens.size()) {
                        stdoutRedirect = tokens.get(i + 1);
                        appendStdout = true;
                    }
                    i++;
                    continue;
                }

                if (token.equals("2>")) {
                    if (i + 1 < tokens.size()) {
                        stderrRedirect = tokens.get(i + 1);
                        appendStderr = false;
                    }
                    i++;
                    continue;
                }

                if (token.equals("2>>")) {
                    if (i + 1 < tokens.size()) {
                        stderrRedirect = tokens.get(i + 1);
                        appendStderr = true;
                    }
                    i++;
                    continue;
                }

                commandTokens.add(token);
            }

            if (!commandTokens.isEmpty() && commandTokens.get(commandTokens.size() - 1).equals("&")) {
                isBackgroundJob = true;
                commandTokens.remove(commandTokens.size() - 1);
            }

            tokens = commandTokens;

            if (stderrRedirect != null) {
                try {
                    File errFile = new File(stderrRedirect);
                    File parent = errFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (PrintWriter writer = new PrintWriter(new FileWriter(errFile, appendStderr))) {
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

                if (stdoutRedirect != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(stdoutRedirect, appendStdout))) {
                        writer.println(output.toString());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(output.toString());
                }
            } else if (command.equals("pwd")) {
                if (stdoutRedirect != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(stdoutRedirect, appendStdout))) {
                        writer.println(currentDirectory.getAbsolutePath());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(currentDirectory.getAbsolutePath());
                }
            } else if (command.equals("cd")) {
                if (tokens.size() < 2) {
                    continue;
                }
                try {
                    File targetDir;
                    String path = tokens.get(1);
                    if (path.equals("~")) {
                        targetDir = new File(System.getenv("HOME"));
                    } else if (new File(path).isAbsolute()) {
                        targetDir = new File(path);
                    } else {
                        targetDir = new File(currentDirectory, path);
                    }
                    targetDir = targetDir.getCanonicalFile();
                    if (targetDir.exists() && targetDir.isDirectory()) {
                        currentDirectory = targetDir;
                    } else {
                        System.out.println("cd: " + path + ": No such file or directory");
                    }
                } catch (Exception e) {
                    System.out.println("cd: " + tokens.get(1) + ": No such file or directory");
                }
            } else if (command.equals("jobs")) {
                List<Job> snapshot = new ArrayList<>(jobs);
                List<Job> completedJobs = new ArrayList<>();
                for (int i = 0; i < snapshot.size(); i++) {
                    Job job = snapshot.get(i);
                    if (!job.process.isAlive()) {
                        job.status = "Done";
                    }
                    String commandText = job.command;
                    if (job.status.equals("Done")) {
                        commandText = commandText.replaceAll("\\s*&\\s*$", "");
                    }
                    String marker = " ";
                    if (i == snapshot.size() - 1) {
                        marker = "+";
                    } else if (i == snapshot.size() - 2) {
                        marker = "-";
                    }
                    System.out.printf("[%d]%s  %-24s%s%n", job.jobNumber, marker, job.status, commandText);
                    if (job.status.equals("Done")) {
                        completedJobs.add(job);
                    }
                }
                jobs.removeAll(completedJobs);
                continue;
            } else if (command.equals("type")) {
                if (tokens.size() < 2) {
                    continue;
                }
                String target = tokens.get(1);
                String result;

                if (target.equals("echo") || target.equals("exit") || target.equals("type") || target.equals("pwd") || target.equals("cd") || target.equals("jobs")) {
                    result = target + " is a shell builtin";
                } else {
                    String executablePath = findExecutable(target);
                    if (executablePath != null) {
                        result = target + " is " + executablePath;
                    } else {
                        result = target + ": not found";
                    }
                }

                if (stdoutRedirect != null) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(stdoutRedirect, appendStdout))) {
                        writer.println(result);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(result);
                }
            } else {
                String executablePath = findExecutable(command);
                if (executablePath != null) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(tokens);
                        pb.directory(currentDirectory);

                        if (stdoutRedirect != null) {
                            if (appendStdout) {
                                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(stdoutRedirect)));
                            } else {
                                pb.redirectOutput(new File(stdoutRedirect));
                            }
                        }

                        if (stderrRedirect != null) {
                            if (appendStderr) {
                                pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(stderrRedirect)));
                            } else {
                                pb.redirectError(new File(stderrRedirect));
                            }
                        }

                        if (stdoutRedirect == null && stderrRedirect == null) {
                            pb.inheritIO();
                        } else if (stdoutRedirect == null) {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        } else if (stderrRedirect == null) {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process process = pb.start();
                        if (isBackgroundJob) {
                            int jobNumber = getNextJobNumber(jobs);
                            jobs.add(new Job(jobNumber, process.pid(), String.join(" ", tokens) + " &", "Running", process));
                            System.out.println("[" + jobNumber + "] " + process.pid());
                        } else {
                            process.waitFor();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(input + ": command not found");
                }
            }
        }
    }
}


