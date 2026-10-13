-- Local demo accounts only. Password: Ds03Demo!2026
SET time_zone = '+07:00';
USE user_db;
START TRANSACTION;
INSERT INTO users (id,name,email,phone,password_hash,role,status,created_at) SELECT '9ca57d7c-7ea4-4d01-8d5e-95994c747d17','Admin Demo','admin@ds03.test','0900000001','$2a$10$o4tlMcEHH8aQJV/JvJClCO1BG5393k0GlKompDH0GqR6SrD8gDhYq','ADMIN','ACTIVE',CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM users WHERE email='admin@ds03.test');
INSERT INTO users (id,name,email,phone,password_hash,role,status,created_at) SELECT '68ac885e-1469-4175-b845-11438893c3ae','Driver One','driver1@ds03.test','0900000002','$2a$10$o4tlMcEHH8aQJV/JvJClCO1BG5393k0GlKompDH0GqR6SrD8gDhYq','DRIVER','ACTIVE',CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM users WHERE email='driver1@ds03.test');
INSERT INTO users (id,name,email,phone,password_hash,role,status,created_at) SELECT '4dc7a8b6-8a7f-44f1-9213-73b365cf40c5','Driver Two','driver2@ds03.test','0900000003','$2a$10$o4tlMcEHH8aQJV/JvJClCO1BG5393k0GlKompDH0GqR6SrD8gDhYq','DRIVER','ACTIVE',CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM users WHERE email='driver2@ds03.test');
INSERT INTO users (id,name,email,phone,password_hash,role,status,created_at) SELECT '386d2d63-acce-4a57-9ae5-c37cb8cd36bd','Driver Three','driver3@ds03.test','0900000004','$2a$10$o4tlMcEHH8aQJV/JvJClCO1BG5393k0GlKompDH0GqR6SrD8gDhYq','DRIVER','ACTIVE',CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM users WHERE email='driver3@ds03.test');
INSERT INTO users (id,name,email,phone,password_hash,role,status,created_at) SELECT '7ea38fbd-354e-408f-8386-ce5e89da6de0','Passenger One','passenger1@ds03.test','0900000005','$2a$10$o4tlMcEHH8aQJV/JvJClCO1BG5393k0GlKompDH0GqR6SrD8gDhYq','PASSENGER','ACTIVE',CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM users WHERE email='passenger1@ds03.test');
INSERT INTO users (id,name,email,phone,password_hash,role,status,created_at) SELECT '38e8bc9c-31d8-493a-a590-e19d3d13a160','Passenger Two','passenger2@ds03.test','0900000006','$2a$10$o4tlMcEHH8aQJV/JvJClCO1BG5393k0GlKompDH0GqR6SrD8gDhYq','PASSENGER','ACTIVE',CURRENT_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM users WHERE email='passenger2@ds03.test');
COMMIT;
SELECT id,name,email,phone,role,status,created_at FROM users WHERE email LIKE '%@ds03.test';
