# API / Worker console logs

The local Spring profile defaults to INFO, including application transaction
manager subclasses. Explicit environment or command-line logger settings can
override this default. For existing jars, set `LOGGING_LEVEL_COM_JSD_AIRD=INFO`
in both processes' persistent launcher configuration and restart them.

For servers redirecting stdout to `/opt/jsd-aird/api.log` and
`/opt/jsd-aird/worker.log`, install `jsd-aird.logrotate` at
`/etc/jsd-aird/logrotate.conf` and invoke it hourly:

```sh
#!/bin/sh
exec /usr/sbin/logrotate --state /var/lib/logrotate/jsd-aird.status /etc/jsd-aird/logrotate.conf
```

Install that executable script as `/etc/cron.hourly/jsd-aird-logrotate`.
Ensure logrotate is installed and the system hourly cron/anacron mechanism is
running. Do not also install this configuration in `/etc/logrotate.d`: that
would run it twice with different state files. If the API output uses another
filename, adjust the configuration to its actual path.

The policy rotates daily or when larger than 50 MiB at the hourly check, keeps
seven archives per file, and compresses all except the newest archive. This is
not a hard size cap: logs can grow between checks. `copytruncate` keeps existing
stdout file descriptors valid; concurrent writes during truncation can lose a
small amount of log output. Application-managed rolling files are preferable
when changing the process launch configuration is practical.

Validate with `logrotate --debug /etc/jsd-aird/logrotate.conf`. The debug command
does not rotate or truncate logs.
