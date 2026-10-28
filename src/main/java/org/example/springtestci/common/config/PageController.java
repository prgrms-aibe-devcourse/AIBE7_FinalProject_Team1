package org.example.springtestci.common.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 화면 이름을 주소창에 직접 입력하거나 새로고침해도 빈 화면 대신 첫 화면(index.html)이 열리게 한다. */
@Controller
public class PageController {

  /** 화면 이름 경로를 단일 페이지 앱의 진입 파일로 넘긴다. */
  @GetMapping({
    "/landing",
    "/home",
    "/passport",
    "/register",
    "/documents",
    "/ai",
    "/market",
    "/sell",
    "/auth",
    "/signup",
    "/mypage",
    "/docmask",
    "/detail",
    "/deals",
    "/receive",
    "/trade"
  })
  String index() {
    return "forward:/index.html";
  }
}
