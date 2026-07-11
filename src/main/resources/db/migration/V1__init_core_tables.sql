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

insert into merchants (id, name, updated_at) values (1, 'xwycx 一次性用品供应商', current_timestamp);
insert into products (id, merchant_id, sku, name, price, stock, hot_score, main_image, detail_images, spec, unit, status, sort_order, updated_at)
values (1, 1, 'MASK-50', '一次性医用口罩 50只装', 12.80, 5000, 96, '/assets/mask-50.png', '/assets/mask-50-detail.png', '50只/盒', '盒', 'ON_SHELF', 100, current_timestamp);
