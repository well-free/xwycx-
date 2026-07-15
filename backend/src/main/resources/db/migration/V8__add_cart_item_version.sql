alter table cart_items
  add column version bigint not null default 0;
