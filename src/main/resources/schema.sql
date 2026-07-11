drop table if exists payment_callbacks;
drop table if exists refund_orders;
drop table if exists payment_orders;
drop table if exists inventory_logs;
drop table if exists customer_order_items;
drop table if exists customer_orders;
drop table if exists shipping_addresses;
drop table if exists user_sessions;
drop table if exists sms_codes;
drop table if exists users;
drop table if exists orders;
drop table if exists trades;
drop table if exists products;
drop table if exists merchants;

create table merchants (
  id bigint primary key,
  name varchar(128) not null,
  updated_at timestamp not null
);

create table products (
  id bigint primary key,
  merchant_id bigint not null,
  sku varchar(64) not null,
  name varchar(128) not null,
  price decimal(19,2) not null,
  stock bigint not null,
  hot_score int not null,
  main_image varchar(512) not null,
  detail_images varchar(1024) not null,
  spec varchar(128) not null,
  unit varchar(32) not null,
  status varchar(32) not null,
  sort_order int not null,
  updated_at timestamp not null
);

create unique index uk_product_sku on products(sku);
create index idx_product_status_sort on products(status, sort_order);

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

create table shipping_addresses (
  id bigint primary key,
  user_id bigint not null,
  receiver_name varchar(64) not null,
  receiver_phone varchar(32) not null,
  detail varchar(255) not null,
  default_address boolean not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

create table customer_orders (
  id bigint primary key,
  order_no varchar(64) not null,
  user_id bigint not null,
  address_id bigint not null,
  total_amount decimal(19,2) not null,
  status varchar(32) not null,
  remark varchar(255),
  version bigint not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  paid_at timestamp,
  shipped_at timestamp,
  canceled_at timestamp
);

create unique index uk_customer_order_no on customer_orders(order_no);
create index idx_customer_order_user_status on customer_orders(user_id, status);

create table customer_order_items (
  id bigint primary key,
  order_id bigint not null,
  product_id bigint not null,
  sku varchar(64) not null,
  product_name varchar(128) not null,
  unit_price decimal(19,2) not null,
  quantity bigint not null,
  subtotal decimal(19,2) not null
);

create index idx_customer_order_items_order on customer_order_items(order_id);

create table inventory_logs (
  id bigint primary key,
  product_id bigint not null,
  order_id bigint not null,
  quantity_change bigint not null,
  reason varchar(64) not null,
  created_at timestamp not null
);

create table orders (
  id bigint primary key,
  symbol varchar(32) not null,
  side varchar(16) not null,
  price decimal(19,2) not null,
  original_quantity bigint not null,
  filled_quantity bigint not null,
  remaining_quantity bigint not null,
  status varchar(32) not null,
  version bigint not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

create table trades (
  id bigint primary key,
  order_id bigint not null,
  symbol varchar(32) not null,
  buy_order_id bigint not null,
  sell_order_id bigint not null,
  price decimal(19,2) not null,
  quantity bigint not null,
  executed_at timestamp not null
);

create table payment_orders (
  id bigint primary key,
  order_id bigint not null,
  gateway_mode varchar(32) default 'mock' not null,
  channel varchar(16) not null,
  amount decimal(19,2) not null,
  status varchar(32) not null,
  channel_trade_no varchar(128),
  pay_url varchar(512),
  qr_code varchar(512),
  version bigint not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  expire_at timestamp not null,
  paid_at timestamp,
  closed_at timestamp
);

create unique index uk_payment_order_channel on payment_orders(order_id, channel);
create index idx_payment_status_expire on payment_orders(status, expire_at);

create table payment_callbacks (
  id bigint primary key,
  payment_id bigint not null,
  channel varchar(16) not null,
  notify_id varchar(128) not null,
  channel_trade_no varchar(128),
  event_type varchar(32) not null,
  amount decimal(19,2) not null,
  payload varchar(1024) not null,
  processed boolean not null,
  created_at timestamp not null
);

create unique index uk_payment_callback_notify on payment_callbacks(channel, notify_id);

create table refund_orders (
  id bigint primary key,
  payment_id bigint not null,
  amount decimal(19,2) not null,
  reason varchar(255) not null,
  status varchar(32) not null,
  channel_refund_no varchar(128),
  created_at timestamp not null,
  updated_at timestamp not null,
  completed_at timestamp
);
