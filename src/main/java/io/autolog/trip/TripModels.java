package io.autolog.trip;

public final class TripModels {
  private TripModels() {}

  public enum State {
    RUNNING,
    COMPLETED,
    INTERRUPTED
  }

  public enum Classification {
    UNCONFIRMED,
    COMMUTE,
    BUSINESS,
    PERSONAL
  }

  public enum Approval {
    NOT_READY,
    PENDING,
    APPROVED,
    CHANGES_REQUESTED
  }

  public enum MeasurementSource {
    PLEOS_ODOMETER,
    UNKNOWN
  }

  public enum CollectionIssue {
    DATA_UNAVAILABLE,
    ODOMETER_DISCONTINUITY,
    SAMPLING_INTERRUPTED
  }
}
