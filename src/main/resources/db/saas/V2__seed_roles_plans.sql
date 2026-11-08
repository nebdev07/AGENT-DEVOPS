-- Rôles système des organisations (ADR-049) et plans de départ (hypothèses commerciales, business.md).
-- Les codes de permission sont stables ; les rôles restent éditables (org_role propres à une organisation).

INSERT INTO org_role (id, organization_id, code, label, permissions, system) VALUES
('00000000-0000-0000-0001-000000000001', NULL, 'ORGANIZATION_OWNER', 'Owner',
 ARRAY['org.read','org.manage','member.read','member.manage','server.read','server.manage','server.analyze',
       'credential.manage','operation.read','operation.propose','operation.approve','audit.read','apitoken.manage',
       'billing.read','billing.manage','managed.read','managed.request','support.grant','ai.use'], true),
('00000000-0000-0000-0001-000000000002', NULL, 'ORGANIZATION_ADMIN', 'Administrator',
 ARRAY['org.read','org.manage','member.read','member.manage','server.read','server.manage','server.analyze',
       'credential.manage','operation.read','operation.propose','operation.approve','audit.read','apitoken.manage',
       'billing.read','managed.read','managed.request','support.grant','ai.use'], true),
('00000000-0000-0000-0001-000000000003', NULL, 'TECHNICAL_MANAGER', 'Technical manager',
 ARRAY['org.read','member.read','server.read','server.manage','server.analyze','credential.manage',
       'operation.read','operation.propose','operation.approve','audit.read','managed.read','managed.request',
       'support.grant','ai.use'], true),
('00000000-0000-0000-0001-000000000004', NULL, 'DEVELOPER', 'Developer',
 ARRAY['org.read','member.read','server.read','server.analyze','operation.read','operation.propose',
       'audit.read','managed.read','ai.use'], true),
('00000000-0000-0000-0001-000000000005', NULL, 'VIEWER', 'Viewer',
 ARRAY['org.read','member.read','server.read','operation.read','audit.read','managed.read'], true),
('00000000-0000-0000-0001-000000000006', NULL, 'BILLING_MANAGER', 'Billing manager',
 ARRAY['org.read','member.read','billing.read','billing.manage'], true);

INSERT INTO plan (code, name, server_quota, member_quota, features, trial_days, public, active, sort_order) VALUES
('STARTER',  'Starter',  2,   3,   ARRAY['discovery','audit','install','backups'], 14, true, true, 10),
('TEAM',     'Team',     10,  15,  ARRAY['discovery','audit','install','backups','api','approvals','ai'], 14, true, true, 20),
('BUSINESS', 'Business', 50,  100, ARRAY['discovery','audit','install','backups','api','approvals','ai','four_eyes','priority_support'], 14, true, true, 30),
('MANAGED',  'Managed',  200, 200, ARRAY['discovery','audit','install','backups','api','approvals','ai','four_eyes','managed'], 0, false, true, 40);

-- Montants en unités mineures (centimes ; le franc CFA XOF n'a pas de sous-unité).
INSERT INTO plan_price (plan_code, currency, period, amount_minor) VALUES
('STARTER',  'USD', 'MONTH', 1900),  ('STARTER',  'EUR', 'MONTH', 1800),  ('STARTER',  'XOF', 'MONTH', 12000),
('TEAM',     'USD', 'MONTH', 7900),  ('TEAM',     'EUR', 'MONTH', 7500),  ('TEAM',     'XOF', 'MONTH', 49000),
('BUSINESS', 'USD', 'MONTH', 24900), ('BUSINESS', 'EUR', 'MONTH', 23500), ('BUSINESS', 'XOF', 'MONTH', 155000),
('STARTER',  'USD', 'YEAR', 19000),  ('STARTER',  'EUR', 'YEAR', 18000),  ('STARTER',  'XOF', 'YEAR', 120000),
('TEAM',     'USD', 'YEAR', 79000),  ('TEAM',     'EUR', 'YEAR', 75000),  ('TEAM',     'XOF', 'YEAR', 490000),
('BUSINESS', 'USD', 'YEAR', 249000), ('BUSINESS', 'EUR', 'YEAR', 235000), ('BUSINESS', 'XOF', 'YEAR', 1550000);
