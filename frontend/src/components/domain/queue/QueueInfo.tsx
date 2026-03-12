export function QueueInfo() {
  return (
    <ul className="text-sm text-gray-500 space-y-1 list-disc list-inside">
      <li>페이지를 닫거나 새로고침하면 대기 순서가 초기화될 수 있습니다.</li>
      <li>브라우저를 닫으면 대기열에서 이탈됩니다.</li>
      <li>대기열 통과 후 10분 이내에 좌석을 선택해야 합니다.</li>
    </ul>
  )
}
