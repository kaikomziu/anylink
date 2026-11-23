// 操作中インジケーター（常に最前面の小さな帯）
const t = document.getElementById('text');
t.textContent = new URLSearchParams(location.search).get('text') || '操作されています';
window.anylink.onIndicatorText((s) => (t.textContent = s));
t.onclick = () => window.anylink.indicatorOpen();
document.getElementById('disc').onclick = () => window.anylink.indicatorDisconnect();
