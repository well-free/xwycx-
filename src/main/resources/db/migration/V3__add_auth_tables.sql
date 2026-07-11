create table users (
  id bigint primary key,
  phone varchar(32) not null,
  role varchar(32) not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

create unique index uk_users_phone on users(phone);

create table sms_codes (
  id bigint primary key,
  phone varchar(32) not null,
  code varchar(16) not null,
  consumed boolean not null,
  expire_at timestamp not null,
  created_at timestamp not null
);

create index idx_sms_phone_code on sms_codes(phone, code, consumed, expire_at);

create table user_sessions (
  id bigint primary key,
  user_id bigint not null,
  token varchar(128) not null,
  expire_at timestamp not null,
  created_at timestamp not null
);

create unique index uk_user_session_token on user_sessions(token);
