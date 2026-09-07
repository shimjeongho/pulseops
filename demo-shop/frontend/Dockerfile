# Demo Shop Frontend를 Container 하나로 만든다.
#
# Vite Dev Server를 그대로 띄우지 않는다. Dev Server는 원본 module을 그때그때 변환해 내려보내는 개발
# 도구이고, 여기서 필요한 것은 이미 만들어진 정적 파일을 그대로 주는 일이다. 그리고 Dev Server의
# /api Proxy는 vite.config.js의 server 설정이라 build 결과물에는 존재하지 않는다. 그 자리를 nginx가
# 대신한다.

FROM node:24-alpine AS builder

WORKDIR /app

# package.json과 lock 파일을 source보다 먼저 넣어 dependency 설치 layer를 따로 남긴다.
COPY package.json package-lock.json ./

# install이 아니라 ci를 쓴다. ci는 lock 파일에 적힌 version을 그대로 설치하고 lock 파일을 고치지
# 않으므로, 언제 build해도 같은 dependency 조합이 나온다.
RUN npm ci

COPY . .

RUN npm run build

FROM nginx:1.29-alpine AS runtime

# node_modules와 source는 가져오지 않는다. 브라우저가 받는 것은 dist 안의 결과물뿐이고, 나머지는
# 실행에 쓰이지 않으면서 image에 남아 그대로 노출 대상이 된다.
COPY --from=builder /app/dist /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
