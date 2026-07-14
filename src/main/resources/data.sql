insert into merchants (id, name, updated_at) values (1, 'xwycx 一次性用品供应商', current_timestamp);
insert into products (id, merchant_id, sku, name, price, stock, hot_score, main_image, detail_images, spec, unit, status, sort_order, updated_at)
values (1, 1, 'MASK-50', '一次性医用口罩 50只装', 12.80, 5000, 96, '/assets/mask-50.png', '/assets/mask-50-detail.png', '50只/盒', '盒', 'ON_SHELF', 100, current_timestamp);
