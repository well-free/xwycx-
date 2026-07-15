create table outbox_events (
  id bigint primary key,
  aggregate_type varchar(64) not null,
  aggregate_id bigint not null,
  event_type varchar(64) not null,
  payload varchar(2000) not null,
  status varchar(32) not null,
  available_at timestamp not null,
  attempts int not null,
  last_error varchar(1000),
  created_at timestamp not null,
  updated_at timestamp not null,
  processed_at timestamp
);

create unique index uk_outbox_aggregate_event
  on outbox_events(aggregate_type, aggregate_id, event_type);
create index idx_outbox_status_available on outbox_events(status, available_at);
