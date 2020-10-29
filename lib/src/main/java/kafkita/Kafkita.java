/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package kafkita;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import kafka.Kafka;
import kafka.admin.BrokerApiVersionsCommand;
import org.apache.commons.cli.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.zookeeper.client.FourLetterWordMain;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;

public class Kafkita {

  public static final String DEFAULT_STACK_NAME = "kafkita-default";

  public static Map<String, ZkService> zkServices = new HashMap<>();
  public static Map<String, KafkaService> kafkaServices = new HashMap<>();

  public static void main(String[] args) throws Exception {

    if (args.length < 1) throw new MissingArgumentException("No command specified.");

    String command = args[0].trim();

    if (Objects.equals(command, "propTemplate")) {
      mainPropTemplate(Arrays.copyOfRange(args, 1, args.length));
    } else if (Objects.equals(command, "zk")) {
      mainZk(Arrays.copyOfRange(args, 1, args.length));
    } else if (Objects.equals(command, "kafka")) {
      mainKafka(Arrays.copyOfRange(args, 1, args.length));
    } else if (Objects.equals(command, "stack")) {
      mainStack(Arrays.copyOfRange(args, 1, args.length));
    } else {
      throw new UnrecognizedOptionException(String.format("Unknown command '%s'", command));
    }
  }

  public static void mainStack(String[] args) throws Exception {

    Options options = new Options();

    options.addOption("name", true, "Name of Kafkita stack instance");
    options.addOption("instanceDir", true, "Directory for kafkita instance state");
    options.addOption("maxTries", true, "Max number of times to attempt to start");
    options.addOption("minWaitOnFailedStartSecs", true, "Duration to wait after failure to start");
    options.addOption(
        "minWaitOnFailedHealthSecs", true, "Duration to wait after health check fails");
    options.addOption("noWait", false, "Don't wait, return to the .main() caller");
    options.addOption("zkPortRange", true, "Zookeeper Port Range");

    CommandLineParser parser = new DefaultParser();
    CommandLine cl = parser.parse(options, args);

    String name = DEFAULT_STACK_NAME;
    if (cl.hasOption("name")) {
      name = cl.getOptionValue("name");
    }

    File instanceDir = new File("." + name);
    if (cl.hasOption("instanceDir")) {
      instanceDir = new File(cl.getOptionValue("instanceDir"));
    }

    int maxTries = Integer.MAX_VALUE;
    if (cl.hasOption("maxTries")) {
      maxTries = Integer.parseInt(cl.getOptionValue("maxTries"));
    }

    Duration minWaitOnFailedStart = Duration.ofSeconds(5);
    if (cl.hasOption("minWaitOnFailedStartSecs")) {
      minWaitOnFailedStart =
          Duration.ofSeconds(Integer.parseInt(cl.getOptionValue("minWaitOnFailedStartSecs")));
    }

    Duration minWaitOnFailedHealth = Duration.ofSeconds(5);
    if (cl.hasOption("minWaitOnFailedHealthSecs")) {
      minWaitOnFailedHealth =
          Duration.ofSeconds(Integer.parseInt(cl.getOptionValue("minWaitOnFailedHealthSecs")));
    }

    int[] zkPortRange = null;
    if (cl.hasOption("zkPortRange")) {
      String[] zkPortRangeStr = cl.getOptionValue("zkPortRange").split(":");
      zkPortRange =
          new int[] {Integer.parseInt(zkPortRangeStr[0]), Integer.parseInt(zkPortRangeStr[1])};
    } else {
      zkPortRange = new int[] {12100, 12200};
    }

    int[] kafkaPortRange = null;
    if (cl.hasOption("kafkaPortRange")) {
      String[] zkPortRangeStr = cl.getOptionValue("kafkaPortRange").split(":");
      kafkaPortRange =
          new int[] {Integer.parseInt(zkPortRangeStr[0]), Integer.parseInt(zkPortRangeStr[1])};
    } else {
      kafkaPortRange = new int[] {19000, 19100};
    }

    boolean noWait = cl.hasOption("noWait");

    ZkService zkService = new ZkService(name, instanceDir, zkPortRange);
    zkService.start(maxTries, minWaitOnFailedStart, minWaitOnFailedHealth);
    zkServices.put(name, zkService);

    KafkaService kafkaService = new KafkaService(name, instanceDir, kafkaPortRange, zkService);
    kafkaService.start(maxTries, minWaitOnFailedStart, minWaitOnFailedHealth);
    kafkaServices.put(name, kafkaService);

    if (noWait) return;
    while (true) {
      Thread.sleep(100);
    }
  }

  public static Process execJavaProcess(Class clazz, List<String> jvmArgs, List<String> args)
      throws IOException {

    String javaHome = System.getProperty("java.home");
    String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
    String classpath = System.getProperty("java.class.path");
    String className = clazz.getName();

    List<String> command = new ArrayList<>();
    command.add(javaBin);
    command.addAll(jvmArgs);
    command.add("-cp");
    command.add(classpath);
    command.add(className);
    command.addAll(args);

    ProcessBuilder builder = new ProcessBuilder(command);
    Process process = builder.inheritIO().start();
    Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
    return process;
  }

  public static String readResourceAsString(String resourcePath) {
    return new BufferedReader(
            new InputStreamReader(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath),
                StandardCharsets.UTF_8))
        .lines()
        .collect(Collectors.joining("\n"));
  }

  public static String renderTemplate(String template, Map<String, String> vars) {
    template = StringSubstitutor.replaceSystemProperties(template);
    return new StringSubstitutor(vars).replace(template);
  }

  static class ZkService {

    final String serviceId;
    final File serviceDir;
    final File dataDir;
    final int[] portRange;

    Integer port = null;
    String props = null;
    String[] args = null;
    Process process = null;

    public ZkService(String name, File instanceDir, int[] portRange) {
      this.serviceId = name + "-zk";
      this.serviceDir = new File(instanceDir, "zk");
      this.dataDir = new File(serviceDir, "data");
      this.portRange = portRange;
    }

    public void start(int maxTries, Duration minWaitOnFailedStart, Duration minWaitOnFailedHealth)
        throws InterruptedException {

      for (int i = 0; i < maxTries; ++i) {

        try {
          if (tryStart(minWaitOnFailedHealth)) return;
        } catch (Exception ex) {
          ExceptionUtils.printRootCauseStackTrace(ex);
        }

        Thread.sleep(minWaitOnFailedStart.getSeconds() * 1000);
      }

      throw new RuntimeException("Could not start zk service, too many retries!");
    }

    public boolean tryStart(Duration minWaitOnFailedHealth)
        throws IOException, InterruptedException {

      this.dataDir.mkdirs();

      this.port = nextFreePort(this.serviceId, this.portRange[0], this.portRange[1]);

      Map<String, String> zkMap = new HashMap<>();
      zkMap.put("kafkita.zk.clientPort", "" + this.port);
      zkMap.put("kafkita.zk.dataDir", this.dataDir.getAbsolutePath());

      File propFile = new File(this.serviceDir, "zk.properties");
      props = renderTemplate(readResourceAsString("zk.template.properties"), zkMap);
      FileUtils.writeStringToFile(propFile, props, StandardCharsets.UTF_8);

      this.args =
          new String[] {QuorumPeerMain.class.getCanonicalName(), propFile.getAbsolutePath()};

      process =
          execJavaProcess(
              WatchfulSubprocess.class, Collections.EMPTY_LIST, Arrays.asList(this.args));

      if (!waitUntilHealthy(minWaitOnFailedHealth)) {
        process.destroyForcibly();
        process = null;
      }

      return process != null;
    }

    public boolean waitUntilHealthy(Duration minWaitOnFailedHealth) throws InterruptedException {
      while (true) {

        if (!process.isAlive()) return false;

        Exception ex = null;
        try {
          FourLetterWordMain.send4LetterWord("localhost", this.port, "srvr");
          return true;
        } catch (IOException ioEx) {
          ex = ioEx;
        } catch (X509Exception.SSLContextException sslEx) {
          ex = sslEx;
        }

        System.out.println(String.format("Health check failed: %s", ex.getMessage()));
        Thread.sleep(minWaitOnFailedHealth.getSeconds() * 1000);
      }
    }

    public int stop(Duration minWaitToForce) throws InterruptedException {

      if (!process.isAlive()) return process.exitValue();

      Instant startWait = null;
      process.destroy();
      while (true) {

        if (!process.isAlive()) return process.exitValue();

        if (startWait == null) {
          startWait = Instant.now();
        } else {
          if (Instant.now().isAfter(startWait.plus(minWaitToForce))) break;
        }

        Thread.sleep(100);
      }

      return process.destroyForcibly().waitFor();
    }
  }

  static class KafkaService {

    final String serviceId;
    final File serviceDir;
    final File logDir;
    final int[] portRange;
    final ZkService zkService;

    Integer port = null;
    String props = null;
    String[] args = null;
    Process process = null;

    public KafkaService(String name, File instanceDir, int[] portRange, ZkService zkService) {
      this.serviceId = name + "-kafka";
      this.serviceDir = new File(instanceDir, "kafka");
      this.logDir = new File(serviceDir, "data");
      this.portRange = portRange;
      this.zkService = zkService;
    }

    public void start(int maxTries, Duration minWaitOnFailedStart, Duration minWaitOnFailedHealth)
        throws InterruptedException {

      for (int i = 0; i < maxTries; ++i) {

        try {
          if (tryStart(minWaitOnFailedHealth)) return;
        } catch (Exception ex) {
          ExceptionUtils.printRootCauseStackTrace(ex);
        }

        Thread.sleep(minWaitOnFailedStart.getSeconds() * 1000);
      }

      throw new RuntimeException("Could not start kafka service, too many retries!");
    }

    public boolean tryStart(Duration minWaitOnFailedHealth)
        throws IOException, InterruptedException {

      this.logDir.mkdirs();

      this.port = nextFreePort(this.serviceId, this.portRange[0], this.portRange[1]);

      Map<String, String> kafkaMap = new HashMap<>();
      kafkaMap.put("kafkita.kafka.zookeeper.connect", "localhost:" + zkService.port);
      kafkaMap.put("kafkita.kafka.port", "" + this.port);
      kafkaMap.put("kafkita.kafka.logDir", this.logDir.getAbsolutePath());

      File propFile = new File(this.serviceDir, "kafka.properties");
      props = renderTemplate(readResourceAsString("kafka.template.properties"), kafkaMap);
      FileUtils.writeStringToFile(propFile, props, StandardCharsets.UTF_8);

      this.args = new String[] {Kafka.class.getCanonicalName(), propFile.getAbsolutePath()};

      process =
          execJavaProcess(
              WatchfulSubprocess.class, Collections.EMPTY_LIST, Arrays.asList(this.args));

      if (!waitUntilHealthy(minWaitOnFailedHealth)) {
        process.destroyForcibly();
        process = null;
      }

      return process != null;
    }

    public boolean waitUntilHealthy(Duration minWaitOnFailedHealth) throws InterruptedException {
      while (true) {

        if (!process.isAlive()) return false;

        Exception ex = null;
        try {
          BrokerApiVersionsCommand.main(
              new String[] {"--bootstrap-server", "localhost:" + this.port});
          return true;
        } catch (Exception anyEx) {
          ex = anyEx;
        }

        System.out.println(String.format("Health check failed: %s", ex.getMessage()));
        Thread.sleep(minWaitOnFailedHealth.getSeconds() * 1000);
      }
    }

    public int stop(Duration minWaitToForce) throws InterruptedException {

      if (!process.isAlive()) return process.exitValue();

      Instant startWait = null;
      process.destroy();
      while (true) {

        if (!process.isAlive()) return process.exitValue();

        if (startWait == null) {
          startWait = Instant.now();
        } else {
          if (Instant.now().isAfter(startWait.plus(minWaitToForce))) break;
        }

        Thread.sleep(100);
      }

      return process.destroyForcibly().waitFor();
    }
  }

  public static void mainPropTemplate(String[] args) throws Exception {

    if (args.length < 2)
      throw new MissingArgumentException("Specify a source template and target filename.");

    String template = FileUtils.readFileToString(new File(args[0]), StandardCharsets.UTF_8);
    template = StringSubstitutor.replaceSystemProperties(template);

    Map<String, String> zkMap = new HashMap<>();
    zkMap.put("kafkita.zk.clientPort", "2181");

    String target = new StringSubstitutor(zkMap).replace(template);

    FileUtils.writeStringToFile(new File(args[1]), target, StandardCharsets.UTF_8);
  }

  public static void mainZk(String[] args) throws Exception {
    QuorumPeerMain.main(args);
  }

  public static void mainKafka(String[] args) throws Exception {
    Kafka.main(args);
  }

  public static int nextFreePort(String serviceId, int portFrom, int portTo) {

    int minPort = Math.min(portFrom, portTo);
    int maxPort = Math.max(portFrom, portTo);

    // The randomization here has the nice property of being stable if the port range is changed, so
    // generally
    // the port choices will be "sticky" for a given appId even if the range gets adjusted.

    byte[] idMd5 = DigestUtils.md5(serviceId);
    long idSeed = ByteBuffer.wrap(idMd5).getLong();
    Random rng = new Random(idSeed);

    class PortChoice {

      final int port;
      final int weight;

      PortChoice(int port, int weight) {
        this.port = port;
        this.weight = weight;
      }
    }

    List<PortChoice> choices = new ArrayList<>(maxPort - minPort);
    for (int p = 0; p < maxPort; ++p) {
      int nextInt = rng.nextInt();
      if (p < minPort) continue; // Assign the same weights to the ports no matter the range
      choices.add(new PortChoice(p, nextInt));
    }

    Collections.sort(choices, (a, b) -> a.weight != b.weight ? (a.weight < b.weight ? -1 : 1) : 0);

    for (PortChoice choice : choices) {
      try {
        new ServerSocket(choice.port).close();
        return choice.port;
      } catch (IOException e) {
        continue;
      }
    }

    return -1;
  }
}