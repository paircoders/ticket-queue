export function QueueInfo() {
  return (
    <ul className="text-sm text-gray-500 space-y-1 list-disc list-inside">
      <li>대기 순서는 서버에서 관리되며, 새로고침해도 유지됩니다.</li>
      <li>브라우저를 닫으면 대기열에서 이탈됩니다.</li>
      <li>대기열 통과 후 10분 이내에 좌석을 선택해야 합니다.</li>
    </ul>
  )
}
