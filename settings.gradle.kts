rootProject.name = "integration"

// Mock 공급사는 본 애플리케이션과 다른 포트로 떠야 하므로 별도 프로세스가 필요하다.
// 같은 포트에 두면 자기 자신을 HTTP 로 호출하게 되어, 스레드가 묶이면서 연동 문제로
// 오해하기 쉬운 실패가 생긴다.
include("mock-supplier")
