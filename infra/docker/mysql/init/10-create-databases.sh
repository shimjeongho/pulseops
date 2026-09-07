#!/bin/sh
# Demo Shop과 PulseOps가 쓸 database와 계정을 만든다.
#
# MySQL 공식 image는 MYSQL_DATABASE로 database를 하나만 만들 수 있는데 여기에는 둘이 필요하다. 두
# Application은 같은 Broker와 같은 MySQL Server를 쓰더라도 서로의 데이터를 읽지 않으며, PulseOps는
# Demo Shop의 Business Database에 접근하지 않는다. 그 경계를 계정 권한으로 강제한다.
#
# .sql이 아니라 .sh인 이유는 credential을 파일에 적지 않기 위해서이다. MySQL entrypoint는 .sql을 그대로
# 실행하므로 그 안의 값은 전부 고정 문자열이 되지만, .sh는 Container 환경 변수를 읽을 수 있다.
#
# 이 script는 data 폴더가 비어 있는 첫 기동에서만 실행된다. volume이 남아 있는 상태로 다시 띄우면
# 실행되지 않으므로, 계정이나 database 이름을 바꾸려면 이미 만들어진 것을 직접 고쳐야 한다.
#
# table은 만들지 않는다. Schema의 근거는 Flyway migration 하나뿐이고, 여기서 DDL을 한 벌 더 두면
# 두 곳이 어긋났을 때 어느 쪽이 맞는지 말할 수 없게 된다.

set -e

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS \`${DEMO_SHOP_DB_NAME}\`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS \`${PULSEOPS_DB_NAME}\`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS '${DEMO_SHOP_DB_USER}'@'%' IDENTIFIED BY '${DEMO_SHOP_DB_PASSWORD}';
CREATE USER IF NOT EXISTS '${PULSEOPS_DB_USER}'@'%' IDENTIFIED BY '${PULSEOPS_DB_PASSWORD}';

-- 각 계정은 자기 database에만 닿는다. Flyway가 table을 만들고 고치므로 DDL 권한까지 필요하다.
GRANT ALL PRIVILEGES ON \`${DEMO_SHOP_DB_NAME}\`.* TO '${DEMO_SHOP_DB_USER}'@'%';
GRANT ALL PRIVILEGES ON \`${PULSEOPS_DB_NAME}\`.* TO '${PULSEOPS_DB_USER}'@'%';

FLUSH PRIVILEGES;
SQL
