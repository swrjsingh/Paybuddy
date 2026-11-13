CREATE TABLE providers (id TEXT PRIMARY KEY,name TEXT NOT NULL,method TEXT NOT NULL,priority INT NOT NULL,enabled BOOLEAN NOT NULL DEFAULT true);
INSERT INTO providers VALUES('upi-a','UPI Atlas','UPI',10,true),('upi-b','UPI Nova','UPI',20,true),('generic','Card & Wallet Sandbox','GENERIC',10,true);
CREATE TABLE policies (merchant_id TEXT NOT NULL,provider_id TEXT NOT NULL REFERENCES providers(id),priority INT NOT NULL,enabled BOOLEAN NOT NULL,PRIMARY KEY(merchant_id,provider_id));
