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
