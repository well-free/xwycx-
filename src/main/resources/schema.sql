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
  name varchar(128) not null,
  price decimal(19,2) not null,
  stock bigint not null,
  hot_score int not null,
  updated_at timestamp not null
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
