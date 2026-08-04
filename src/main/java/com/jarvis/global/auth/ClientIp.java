package com.jarvis.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 접속자 IP 해석 — `X-Forwarded-For`를 <b>오른쪽에서</b> 읽는다.
 *
 * <p>우리 경로는 <code>클라이언트 → ALB → nginx → spring</code>이고 nginx가
 * {@code $proxy_add_x_forwarded_for}로 <b>이어붙인다</b>(덮어쓰지 않는다 — ALB가 앞단이라
 * 덮어쓰면 모든 사용자가 ALB 사설 IP 하나로 보인다). 그래서 헤더는 이렇게 쌓인다:
 *
 * <pre>
 * X-Forwarded-For: [클라이언트가 임의로 넣은 값...], &lt;실제 클라이언트 IP&gt;, &lt;ALB IP&gt;
 *                   └ 위조 가능한 자리                  └ 오른쪽 2번째        └ 오른쪽 1번째
 * </pre>
 *
 * <p><b>맨 왼쪽을 읽으면 안 된다</b> — 클라이언트가 헤더를 직접 채워 보낼 수 있어 IP 기준 제한이
 * 무력화된다. 오른쪽은 우리 인프라가 채운 자리라 위조가 불가능하다.
 *
 * <p>깊이는 홉 수에 달려 있어 <b>환경변수</b>({@code APP_CLIENT_IP_FORWARDED_DEPTH})로 뺀다 —
 * 터널·프록시가 늘면 값이 달라지고, 그때 재배포 없이 설정만 바꿀 수 있어야 한다. 값이 실제보다
 * 작으면 중간 장비 IP가 클라이언트로 잡히고, 크면 위조 가능한 자리를 읽으므로 실측해서 맞춘다.
 */
@Component
public class ClientIp {

    private final int forwardedDepth;

    public ClientIp(@Value("${app.client-ip.forwarded-depth}") int forwardedDepth) {
        this.forwardedDepth = forwardedDepth;
    }

    public String resolve(HttpServletRequest request) {
        return resolve(request, forwardedDepth);
    }

    /**
     * @param forwardedDepth 오른쪽에서 몇 번째를 클라이언트로 볼지 (1-base). 헤더가 그보다 짧으면
     *                       가장 왼쪽 값으로 떨어진다 — 홉이 예상보다 적은 환경(로컬 등)을 위한 폴백.
     */
    static String resolve(HttpServletRequest request, int forwardedDepth) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] hops = forwarded.split(",");
        String ip = hops[Math.max(0, hops.length - forwardedDepth)].trim();
        return ip.isEmpty() ? request.getRemoteAddr() : ip;
    }
}
