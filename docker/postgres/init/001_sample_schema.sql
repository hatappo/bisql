DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO bisql;
GRANT ALL ON SCHEMA public TO public;

CREATE TABLE users (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX users_status_idx ON users (status);

CREATE TABLE orders (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users (id),
  order_number TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL DEFAULT 'pending',
  total_amount NUMERIC(12, 2) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX orders_user_id_idx ON orders (user_id);
CREATE INDEX orders_state_created_at_idx ON orders (state, created_at);

CREATE TABLE user_roles (
  user_id BIGINT NOT NULL REFERENCES users (id),
  role_code TEXT NOT NULL,
  granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  granted_by BIGINT REFERENCES users (id),
  PRIMARY KEY (user_id, role_code)
);

CREATE TABLE user_devices (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users (id),
  device_type TEXT NOT NULL,
  device_identifier TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, device_identifier)
);

CREATE INDEX user_devices_status_last_seen_at_idx
  ON user_devices (status, last_seen_at);

CREATE INDEX user_devices_user_id_last_seen_at_idx
  ON user_devices (user_id, last_seen_at);

CREATE INDEX user_devices_status_device_type_last_seen_at_idx
  ON user_devices (status, device_type, last_seen_at);

INSERT INTO users (email, display_name, status)
VALUES
  ('alice@example.com', 'Alice', 'active'),
  ('bob@example.com', 'Bob', 'invited'),
  ('carol@example.com', 'Carol', 'active');

INSERT INTO orders (user_id, order_number, state, total_amount)
VALUES
  (1, 'ORD-1001', 'paid', 120.50),
  (1, 'ORD-1002', 'pending', 42.00),
  (2, 'ORD-1003', 'cancelled', 75.25);

INSERT INTO user_roles (user_id, role_code, granted_by)
VALUES
  (1, 'admin', 1),
  (1, 'billing', 2),
  (2, 'support', 1);

INSERT INTO user_devices (user_id, device_type, device_identifier, status)
VALUES
  (1, 'ios', 'alice-iphone', 'active'),
  (1, 'web', 'alice-browser', 'inactive'),
  (2, 'android', 'bob-pixel', 'active');
