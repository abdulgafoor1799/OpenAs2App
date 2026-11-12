# OpenAS2 4.8.2 (pre-2026-11-08): effective properties

Source: VM backup `/home/ubuntu/backups/2026-11-09-full/deployments.tgz` (the 4.8.2 config folder, unchanged since
April 2026). Container `EDIPLUG_AS2_SERVER`, image `openas2:ediplug-arm64` (OpenAS2 4.8.2). Its environment came from
`deployments/docker-compose-ediplug.yml` + `.env`: `OPENAS2_PROPERTIES_FILE=/opt/openas2/config/openas2.properties`,
`AS2_KEYSTORE_PASSWORD`, `AS2_DB_PASSWORD` (= `.env` `AS2_DB_PASSWORD_OPENAS2_USER`), `SMTP_USER`, `SMTP_PASS`,
`SMTP_HOST` (smtp.gmail.com), `SMTP_PORT` (587). Mounted: `config/` (rw), `data/` (rw), the PostgreSQL JDBC jar.
Partnerships came from `config/partnerships.xml`, keys/certificates from `config/as2_certs.p12`.
Secrets are shown only as `$ENV{...}` references or OpenAS2's stock placeholders; no real value is in this file.
Effective values = `config/config.xml` `<properties>` defaults, overridden by config/openas2.properties; OPENAS2_PROPERTIES_FILE=/opt/openas2/config/openas2.properties).

| Property | Effective value | Source | config.xml default it replaced |
|---|---|---|---|
| `config.dir` | `%home%` | config.xml default |  |
| `storageBaseDir` | `%home%/../data` | config.xml default |  |
| `partnership_file` | `%home%/partnerships.xml` | config.xml default |  |
| `log_date_format` | `yyyy-MM-dd HH:mm:ss.SSS` | config.xml default |  |
| `sql_timestamp_format` | `yyyy-MM-dd HH:mm:ss.SSS` | config.xml default |  |
| `as2_message_id_format` | `msg.$date.yyyy-MM-dd-HHmmss$.$rand.UUID$.$msg.sender.as2_id$.to.$msg.receiver.as2_id$` | openas2.properties | `$date.yyyyMMddHHmmssZ$-$rand.123$-$msg.sender.as2_id$_$msg.receiver.as2_id$_$msg.attributes.filename$` |
| `as2_receive_message_filename_fallback` | `payload-$date.yyyy-MM-dd-HHmmss$-$rand.shortUUID$.edi` | openas2.properties | `$rand.shortUUID$` |
| `as2_async_mdn_url` | `https://as2.ediplug.com/mdn` | openas2.properties | `http://localhost:$properties.async_mdn_receiver_port$` |
| `as2_keystore` | `$properties.configDir$/as2_certs.p12` | openas2.properties | `%home%/as2_certs.p12` |
| `as2_keystore_password` | `$ENV{AS2_KEYSTORE_PASSWORD}` | openas2.properties | `testas2` |
| `as2_keystore.refresh_interval` | `300` | config.xml default |  |
| `ssl_trust_keystore.enabled` | `false` | config.xml default |  |
| `ssl_trust_keystore` | `%home%/ssl_trust_certs.p12` | config.xml default |  |
| `ssl_trust_keystore_password` | `testas2  (stock placeholder)` | config.xml default |  |
| `ssl_trust_keystore.refresh_interval` | `300` | config.xml default |  |
| `ssl_keystore` | `%home%/ssl_certs.jks` | config.xml default |  |
| `ssl_keystore_password` | `testas2  (stock placeholder)` | config.xml default |  |
| `console.command.processor.enabled` | `false` | config.xml default |  |
| `restapi.command.processor.enabled` | `false` | config.xml default |  |
| `restapi.command.processor.baseuri` | `http://localhost:8080` | config.xml default |  |
| `restapi.command.processor.userid` | `userID` | config.xml default |  |
| `restapi.command.processor.password` | `pWd  (stock placeholder)` | config.xml default |  |
| `processor.resend_max_retries` | `5` | config.xml default |  |
| `module.AS2SenderModule.enabled` | `true` | config.xml default |  |
| `module.AS2SenderModule.readtimeout` | `60000` | config.xml default |  |
| `module.MDNSenderModule.enabled` | `true` | config.xml default |  |
| `module.DbTrackingModule.enabled` | `true` | config.xml default |  |
| `module.MDNFileModule.enabled` | `true` | config.xml default |  |
| `module.MDNFileModule.filename` | `$properties.storageBaseDir$/mdn/$mdn.msg.sender.as2_id$/$mdn.msg.receiver.as2_id$/$date.yyyy-MM-dd$/$mdn.attributes.ORIGINAL_MESSAGE_ID$/$mdn.headers.message-id$.mdn.txt` | openas2.properties | `$properties.storageBaseDir$/$mdn.msg.sender.as2_id$-$mdn.msg.receiver.as2_id$/mdn/$date.yyyy-MM-dd$/$mdn.msg.headers.message-id$` |
| `module.MDNFileModule.tempdir` | `$properties.storageBaseDir$/temp` | config.xml default |  |
| `module.MessageFileModule.enabled` | `true` | config.xml default |  |
| `module.MessageFileModule.filename` | `$properties.storageBaseDir$/inbox/$msg.receiver.as2_id$/$msg.sender.as2_id$/$date.yyyy-MM-dd$/$msg.headers.message-id$/$msg.content-disposition.filename$` | openas2.properties | `$properties.storageBaseDir$/$msg.sender.as2_id$-$msg.receiver.as2_id$/inbox/$msg.headers.message-id$` |
| `module.MessageFileModule.header` | `$properties.storageBaseDir$/inbox/$msg.receiver.as2_id$/$msg.sender.as2_id$/$date.yyyy-MM-dd$/$msg.headers.message-id$/headers.txt` | openas2.properties | `$properties.storageBaseDir$/$msg.sender.as2_id$-$msg.receiver.as2_id$/msgheaders/$date.yyyy-MM-dd$/$msg.headers.message-id$` |
| `module.MessageFileModule.tempdir` | `$properties.storageBaseDir$/temp` | config.xml default |  |
| `module.DirectoryResenderModule.enabled` | `true` | config.xml default |  |
| `module.DirectoryResenderModule.resenddelay` | `60` | config.xml default |  |
| `module.AS2ReceiverModule.http.enabled` | `true` | config.xml default |  |
| `module.AS2ReceiverModule.http.port` | `10080` | config.xml default |  |
| `module.AS2MDNReceiverModule.http.enabled` | `true` | config.xml default |  |
| `module.AS2MDNReceiverModule.http.port` | `10081` | config.xml default |  |
| `module.AS2ReceiverModule.https.enabled` | `false` | config.xml default |  |
| `module.AS2ReceiverModule.https.port` | `10443` | config.xml default |  |
| `module.AS2MDNReceiverModule.https.enabled` | `false` | config.xml default |  |
| `module.AS2MDNReceiverModule.https.port` | `10444` | config.xml default |  |
| `module.HealthCheckModule.enabled` | `true` | openas2.properties | `false` |
| `module.HealthCheckModule.protocol` | `http` | openas2.properties | `http` |
| `module.HealthCheckModule.address` | `0.0.0.0` | openas2.properties | `localhost` |
| `module.HealthCheckModule.port` | `10099` | openas2.properties | `10099` |
| `module.HealthCheckModule.keystore` | `$properties.ssl_keystore$` | config.xml default |  |
| `module.HealthCheckModule.keystore_password` | `$properties.ssl_keystore_password$` | config.xml default |  |
| `async_mdn_receiver_port` | `$properties.module.AS2MDNReceiverModule.http.port$` | config.xml default |  |
| `jakarta.mail.properties.file` | `%home%/java.mail.properties` | config.xml default |  |
| `email.from` | `Open AS2 Server<as2msgs@openas2.org>` | config.xml default |  |
| `email.to` | `your email address` | config.xml default |  |
| `email.smtpserver` | `mail.openas2.org` | config.xml default |  |
| `email.smtpport` | `25` | config.xml default |  |
| `email.smtpauth` | `true` | config.xml default |  |
| `email.smtpuser` | `mySmtpUserId` | config.xml default |  |
| `email.smtppwd` | `mySmtpPwd  (stock placeholder)` | config.xml default |  |
| `email.subject` | `$exception.name$: $exception.message$` | config.xml default |  |
| `email.bodytemplate` | `%home%/emailtemplate.txt` | config.xml default |  |
| `msg_tracking.use_embedded_db` | `false` | openas2.properties | `true` |
| `msg_tracking.force_load_jdbc_driver` | `false` | config.xml default |  |
| `msg_tracking.db_user` | `openas2` | openas2.properties | `sa` |
| `msg_tracking.db_pwd` | `$ENV{AS2_DB_PASSWORD}` | openas2.properties | `OpenAS2` |
| `msg_tracking.db_name` | `openas2` | openas2.properties | `openas2` |
| `msg_tracking.table_name` | `msg_metadata` | config.xml default |  |
| `msg_tracking.db_directory` | `%home%/DB` | config.xml default |  |
| `msg_tracking.jdbc_driver` | `org.postgresql.Driver` | openas2.properties | `org.h2.Driver` |
| `msg_tracking.jdbc_connect_string` | `jdbc:postgresql://postgres:5432/$component.db_name$?sslmode=verify-ca&sslrootcert=/opt/openas2/config/db-ca.crt` | openas2.properties | `jdbc:h2:$component.db_directory$/$component.db_name$` |
| `msg_tracking.sql_escape_character` | `'` | config.xml default |  |
| `msg_tracking.tcp_server_start` | `true` | config.xml default |  |
| `msg_tracking.tcp_server_port` | `9092` | config.xml default |  |
| `msg_tracking.tcp_server_password` | `openas2  (stock placeholder)` | config.xml default |  |
| `reject_unsigned_messages` | `false` | config.xml default |  |
| `pollerConfigBase.outboxdir` | `$properties.storageBaseDir$/outbox/$partnership.sender.as2_id$/$partnership.receiver.as2_id$` | openas2.properties | `$properties.storageBaseDir$/outbox/$partnership.receiver.as2_id$` |
| `pollerConfigBase.errordir` | `$properties.storageBaseDir$/outbox/error/$date.yyyy-MM-dd$/$partnership.sender.as2_id$/$partnership.receiver.as2_id$` | openas2.properties | `$properties.storageBaseDir$/outbox/error/$date.YYYY$-$date.MM$-$date.dd$/$partnership.receiver.as2_id$` |
| `pollerConfigBase.interval` | `5` | config.xml default |  |
| `pollerConfigBase.defaults` | `sender.as2_id=$partnership.sender.as2_id$, receiver.as2_id=$partnership.receiver.as2_id$` | config.xml default |  |
| `pollerConfigBase.sendfilename` | `true` | config.xml default |  |
| `pollerConfigBase.mimetype` | `application/EDI-X12` | config.xml default |  |
| `pollerConfigBase.process_files_in_parallel` | `true` | openas2.properties | `false` |
| `pollerConfigBase.max_parallel_files` | `5` | openas2.properties | `20` |
| `pollerConfigBase.max_file_processing_time_minutes` | `30` | config.xml default |  |
| `partnerships.polling.interval` | `120` | config.xml default |  |
| `messages.enabled` | `false` | config.xml default |  |
| `messages.polling.interval` | `120` | config.xml default |  |
| `configDir` | `/opt/openas2/config` | openas2.properties |  |
| `pollerConfigBase.fileextensionfilter` | `edi` | openas2.properties |  |
| `as2_mdn_message_id_format` | `mdn.$date.yyyy-MM-dd-HHmmss$.$rand.UUID$.$msg.receiver.as2_id$.to.$msg.sender.as2_id$` | openas2.properties |  |
