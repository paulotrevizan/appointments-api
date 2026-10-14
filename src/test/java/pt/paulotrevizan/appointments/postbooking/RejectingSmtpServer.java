package pt.paulotrevizan.appointments.postbooking;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * An SMTP server that refuses every recipient with {@code 550}: a permanent rejection, which
 * GreenMail cannot produce (it accepts any recipient). Speaks just enough SMTP for JavaMail.
 */
final class RejectingSmtpServer implements AutoCloseable {

  private final ServerSocket serverSocket;

  RejectingSmtpServer(int port) throws IOException {
    serverSocket = new ServerSocket();
    serverSocket.setReuseAddress(true);
    serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    Thread.ofVirtual().start(this::acceptConnections);
  }

  private void acceptConnections() {
    while (!serverSocket.isClosed()) {
      try (Socket client = serverSocket.accept()) {
        converse(client);
      } catch (IOException e) {
        // The client hung up or close() stopped the server; the loop condition tells which.
      }
    }
  }

  private static void converse(Socket client) throws IOException {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(client.getInputStream(), US_ASCII));
    Writer out = new OutputStreamWriter(client.getOutputStream(), US_ASCII);
    reply(out, "220 rejecting-smtp ready");
    for (String line = in.readLine(); line != null; line = in.readLine()) {
      String command = line.toUpperCase(Locale.ROOT);
      if (command.startsWith("RCPT")) {
        reply(out, "550 5.1.1 Mailbox unavailable");
      } else if (command.startsWith("QUIT")) {
        reply(out, "221 Bye");
        return;
      } else {
        reply(out, "250 OK");
      }
    }
  }

  private static void reply(Writer out, String line) throws IOException {
    out.write(line + "\r\n");
    out.flush();
  }

  @Override
  public void close() throws IOException {
    serverSocket.close();
  }
}
