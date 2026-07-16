import { request } from '../api';

export async function loadCommerceData() {
  const [products, orders, payments] = await Promise.all([
    request('/api/products'),
    request('/api/customer-orders'),
    request('/api/payments').catch(() => ({ items: [] }))
  ]);
  return { products: products.items || [], orders: orders.items || [], payments: payments.items || [] };
}

export async function loadProductData() {
  const [products, addresses] = await Promise.all([request('/api/products'), request('/api/addresses')]);
  return { products: products.items || [], addresses: addresses.items || [] };
}

export async function loadOrderData() {
  const [orders, payments] = await Promise.all([
    request('/api/customer-orders'),
    request('/api/payments').catch(() => ({ items: [] }))
  ]);
  return { orders: orders.items || [], payments: payments.items || [] };
}
