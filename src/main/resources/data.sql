insert into merchants (id, name, updated_at) values (1, 'Demo Merchant', current_timestamp);
insert into products (id, merchant_id, name, price, stock, hot_score, updated_at)
values (1, 1, 'Apple Juice', 19.90, 1000, 87, current_timestamp);
