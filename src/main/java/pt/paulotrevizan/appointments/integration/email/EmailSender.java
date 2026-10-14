package pt.paulotrevizan.appointments.integration.email;

/** Email provider (HLD 7.3). The outbox task status prevents a re-send after {@code DONE}. */
public interface EmailSender {

  void send(String to, String subject, String body);
}
