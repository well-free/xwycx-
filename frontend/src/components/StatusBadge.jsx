import { orderStatusLabels, paymentStatusLabels } from '../constants/status';

export default function StatusBadge({ status, payment = false }) {
  const labels = payment ? paymentStatusLabels : orderStatusLabels;
  return <span className={`status-badge ${status}`}>{labels[status] || status}</span>;
}
