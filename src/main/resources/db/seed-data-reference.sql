-- Reference seed data for the `flight` table - NOT auto-run.
--
-- This used to be src/main/resources/data.sql, which Spring Boot auto-executes on
-- every startup (spring.sql.init.mode=always matches classpath:data.sql by name and
-- location). That meant every restart truncated and re-seeded the table, wiping out
-- any flight added at runtime - e.g. via POST /flights - the moment the app restarted.
--
-- Moved here (out of the auto-detected root of the classpath, and renamed) so the app
-- now just uses whatever is already in the table. Apply this file by hand only when
-- you want to reset a database back to this baseline - the TRUNCATE below means doing
-- so discards anything added since, API-created flights included.

TRUNCATE TABLE flight RESTART IDENTITY;

-- Delhi -> Mumbai (DEL -> BOM) : 20 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('AI-4112', 'DEL', 'BOM', 'Air India', 3879.00),
    ('6E-9035', 'DEL', 'BOM', 'IndiGo', 4200.00),
    ('AI-588', 'DEL', 'BOM', 'Air India', 5680.00),
    ('AI-9295', 'DEL', 'BOM', 'Air India', 4460.00),
    ('6E-3711', 'DEL', 'BOM', 'IndiGo', 6189.00),
    ('SG-2715', 'DEL', 'BOM', 'SpiceJet', 5670.00),
    ('IX-2647', 'DEL', 'BOM', 'Air India Express', 5179.00),
    ('6E-1774', 'DEL', 'BOM', 'IndiGo', 6929.00),
    ('AI-5735', 'DEL', 'BOM', 'Air India', 5039.00),
    ('QP-7627', 'DEL', 'BOM', 'Akasa Air', 4610.00),
    ('QP-1391', 'DEL', 'BOM', 'Akasa Air', 4249.00),
    ('QP-9559', 'DEL', 'BOM', 'Akasa Air', 4749.00),
    ('6E-3833', 'DEL', 'BOM', 'IndiGo', 6050.00),
    ('UK-1754', 'DEL', 'BOM', 'Vistara', 6979.00),
    ('SG-2764', 'DEL', 'BOM', 'SpiceJet', 4699.00),
    ('UK-1269', 'DEL', 'BOM', 'Vistara', 4959.00),
    ('QP-2777', 'DEL', 'BOM', 'Akasa Air', 5829.00),
    ('SG-5413', 'DEL', 'BOM', 'SpiceJet', 4999.00),
    ('AI-5268', 'DEL', 'BOM', 'Air India', 4490.00),
    ('SG-9392', 'DEL', 'BOM', 'SpiceJet', 4629.00);

-- Mumbai -> Delhi (BOM -> DEL) : 20 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('UK-6582', 'BOM', 'DEL', 'Vistara', 4459.00),
    ('IX-2387', 'BOM', 'DEL', 'Air India Express', 5199.00),
    ('6E-9677', 'BOM', 'DEL', 'IndiGo', 6119.00),
    ('SG-6030', 'BOM', 'DEL', 'SpiceJet', 6659.00),
    ('6E-8448', 'BOM', 'DEL', 'IndiGo', 6889.00),
    ('SG-1896', 'BOM', 'DEL', 'SpiceJet', 3960.00),
    ('6E-9871', 'BOM', 'DEL', 'IndiGo', 5789.00),
    ('AI-8769', 'BOM', 'DEL', 'Air India', 5009.00),
    ('UK-1976', 'BOM', 'DEL', 'Vistara', 6790.00),
    ('IX-5673', 'BOM', 'DEL', 'Air India Express', 6559.00),
    ('AI-7533', 'BOM', 'DEL', 'Air India', 4629.00),
    ('AI-8301', 'BOM', 'DEL', 'Air India', 6789.00),
    ('6E-4989', 'BOM', 'DEL', 'IndiGo', 5300.00),
    ('IX-2604', 'BOM', 'DEL', 'Air India Express', 5309.00),
    ('UK-9913', 'BOM', 'DEL', 'Vistara', 6120.00),
    ('UK-6047', 'BOM', 'DEL', 'Vistara', 5240.00),
    ('UK-9395', 'BOM', 'DEL', 'Vistara', 4449.00),
    ('AI-1233', 'BOM', 'DEL', 'Air India', 4049.00),
    ('QP-7887', 'BOM', 'DEL', 'Akasa Air', 6139.00),
    ('QP-3570', 'BOM', 'DEL', 'Akasa Air', 4299.00);

-- Delhi -> Bengaluru (DEL -> BLR) : 19 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('IX-6637', 'DEL', 'BLR', 'Air India Express', 7089.00),
    ('IX-8579', 'DEL', 'BLR', 'Air India Express', 6989.00),
    ('SG-1149', 'DEL', 'BLR', 'SpiceJet', 4869.00),
    ('UK-9741', 'DEL', 'BLR', 'Vistara', 4479.00),
    ('6E-3850', 'DEL', 'BLR', 'IndiGo', 4420.00),
    ('AI-1260', 'DEL', 'BLR', 'Air India', 7919.00),
    ('QP-3610', 'DEL', 'BLR', 'Akasa Air', 5409.00),
    ('QP-4081', 'DEL', 'BLR', 'Akasa Air', 4999.00),
    ('SG-1645', 'DEL', 'BLR', 'SpiceJet', 7509.00),
    ('AI-7039', 'DEL', 'BLR', 'Air India', 6969.00),
    ('SG-1712', 'DEL', 'BLR', 'SpiceJet', 6190.00),
    ('AI-1890', 'DEL', 'BLR', 'Air India', 5989.00),
    ('6E-2396', 'DEL', 'BLR', 'IndiGo', 5229.00),
    ('SG-4192', 'DEL', 'BLR', 'SpiceJet', 5199.00),
    ('AI-928', 'DEL', 'BLR', 'Air India', 6090.00),
    ('IX-1628', 'DEL', 'BLR', 'Air India Express', 8230.00),
    ('6E-7986', 'DEL', 'BLR', 'IndiGo', 5129.00),
    ('6E-2797', 'DEL', 'BLR', 'IndiGo', 7710.00),
    ('SG-4445', 'DEL', 'BLR', 'SpiceJet', 4499.00);

-- Bengaluru -> Delhi (BLR -> DEL) : 18 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('UK-2636', 'BLR', 'DEL', 'Vistara', 6019.00),
    ('6E-9589', 'BLR', 'DEL', 'IndiGo', 5440.00),
    ('IX-1036', 'BLR', 'DEL', 'Air India Express', 6419.00),
    ('AI-1031', 'BLR', 'DEL', 'Air India', 6539.00),
    ('QP-1222', 'BLR', 'DEL', 'Akasa Air', 4629.00),
    ('QP-6715', 'BLR', 'DEL', 'Akasa Air', 4589.00),
    ('AI-9585', 'BLR', 'DEL', 'Air India', 7889.00),
    ('QP-6968', 'BLR', 'DEL', 'Akasa Air', 4470.00),
    ('IX-4372', 'BLR', 'DEL', 'Air India Express', 6579.00),
    ('6E-4010', 'BLR', 'DEL', 'IndiGo', 6899.00),
    ('UK-7591', 'BLR', 'DEL', 'Vistara', 5869.00),
    ('UK-252', 'BLR', 'DEL', 'Vistara', 7830.00),
    ('SG-1300', 'BLR', 'DEL', 'SpiceJet', 6670.00),
    ('QP-2270', 'BLR', 'DEL', 'Akasa Air', 5179.00),
    ('UK-6154', 'BLR', 'DEL', 'Vistara', 7659.00),
    ('UK-8766', 'BLR', 'DEL', 'Vistara', 4969.00),
    ('AI-1797', 'BLR', 'DEL', 'Air India', 6889.00),
    ('6E-9164', 'BLR', 'DEL', 'IndiGo', 5320.00);

-- Mumbai -> Goa (BOM -> GOI) : 16 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('UK-5717', 'BOM', 'GOI', 'Vistara', 2609.00),
    ('6E-8380', 'BOM', 'GOI', 'IndiGo', 3389.00),
    ('SG-1612', 'BOM', 'GOI', 'SpiceJet', 2550.00),
    ('IX-822', 'BOM', 'GOI', 'Air India Express', 2909.00),
    ('AI-4391', 'BOM', 'GOI', 'Air India', 2709.00),
    ('6E-9289', 'BOM', 'GOI', 'IndiGo', 3539.00),
    ('AI-9038', 'BOM', 'GOI', 'Air India', 2299.00),
    ('AI-7141', 'BOM', 'GOI', 'Air India', 3619.00),
    ('6E-753', 'BOM', 'GOI', 'IndiGo', 2209.00),
    ('UK-1784', 'BOM', 'GOI', 'Vistara', 2479.00),
    ('UK-2632', 'BOM', 'GOI', 'Vistara', 3609.00),
    ('6E-6855', 'BOM', 'GOI', 'IndiGo', 3679.00),
    ('AI-6845', 'BOM', 'GOI', 'Air India', 2469.00),
    ('IX-4471', 'BOM', 'GOI', 'Air India Express', 3679.00),
    ('6E-6367', 'BOM', 'GOI', 'IndiGo', 3530.00),
    ('AI-3369', 'BOM', 'GOI', 'Air India', 3669.00);

-- Goa -> Mumbai (GOI -> BOM) : 18 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('UK-3752', 'GOI', 'BOM', 'Vistara', 2609.00),
    ('AI-5478', 'GOI', 'BOM', 'Air India', 3339.00),
    ('UK-5853', 'GOI', 'BOM', 'Vistara', 3649.00),
    ('IX-552', 'GOI', 'BOM', 'Air India Express', 3019.00),
    ('AI-3025', 'GOI', 'BOM', 'Air India', 3679.00),
    ('QP-726', 'GOI', 'BOM', 'Akasa Air', 3829.00),
    ('AI-5239', 'GOI', 'BOM', 'Air India', 3169.00),
    ('SG-6411', 'GOI', 'BOM', 'SpiceJet', 3140.00),
    ('QP-7244', 'GOI', 'BOM', 'Akasa Air', 2390.00),
    ('AI-6067', 'GOI', 'BOM', 'Air India', 2999.00),
    ('SG-5243', 'GOI', 'BOM', 'SpiceJet', 2229.00),
    ('IX-8408', 'GOI', 'BOM', 'Air India Express', 3619.00),
    ('UK-6692', 'GOI', 'BOM', 'Vistara', 3299.00),
    ('IX-3243', 'GOI', 'BOM', 'Air India Express', 2589.00),
    ('SG-2951', 'GOI', 'BOM', 'SpiceJet', 3349.00),
    ('QP-9077', 'GOI', 'BOM', 'Akasa Air', 3169.00),
    ('AI-7143', 'GOI', 'BOM', 'Air India', 2609.00),
    ('QP-7718', 'GOI', 'BOM', 'Akasa Air', 3189.00);

-- Delhi -> Kolkata (DEL -> CCU) : 18 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('SG-2880', 'DEL', 'CCU', 'SpiceJet', 6519.00),
    ('IX-1630', 'DEL', 'CCU', 'Air India Express', 4379.00),
    ('6E-3362', 'DEL', 'CCU', 'IndiGo', 6419.00),
    ('6E-7884', 'DEL', 'CCU', 'IndiGo', 4129.00),
    ('QP-7561', 'DEL', 'CCU', 'Akasa Air', 7030.00),
    ('SG-6391', 'DEL', 'CCU', 'SpiceJet', 7169.00),
    ('SG-190', 'DEL', 'CCU', 'SpiceJet', 5449.00),
    ('AI-2981', 'DEL', 'CCU', 'Air India', 6789.00),
    ('IX-9232', 'DEL', 'CCU', 'Air India Express', 5860.00),
    ('6E-7578', 'DEL', 'CCU', 'IndiGo', 7270.00),
    ('6E-7351', 'DEL', 'CCU', 'IndiGo', 6909.00),
    ('QP-9076', 'DEL', 'CCU', 'Akasa Air', 7009.00),
    ('SG-7473', 'DEL', 'CCU', 'SpiceJet', 7299.00),
    ('UK-8640', 'DEL', 'CCU', 'Vistara', 6729.00),
    ('SG-7306', 'DEL', 'CCU', 'SpiceJet', 6289.00),
    ('AI-4551', 'DEL', 'CCU', 'Air India', 6559.00),
    ('UK-2367', 'DEL', 'CCU', 'Vistara', 5160.00),
    ('6E-3605', 'DEL', 'CCU', 'IndiGo', 4859.00);

-- Kolkata -> Delhi (CCU -> DEL) : 15 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('SG-6912', 'CCU', 'DEL', 'SpiceJet', 5469.00),
    ('AI-6481', 'CCU', 'DEL', 'Air India', 4779.00),
    ('QP-9532', 'CCU', 'DEL', 'Akasa Air', 7240.00),
    ('SG-4992', 'CCU', 'DEL', 'SpiceJet', 5659.00),
    ('SG-8918', 'CCU', 'DEL', 'SpiceJet', 7019.00),
    ('IX-8099', 'CCU', 'DEL', 'Air India Express', 6519.00),
    ('6E-575', 'CCU', 'DEL', 'IndiGo', 5009.00),
    ('SG-2804', 'CCU', 'DEL', 'SpiceJet', 5219.00),
    ('SG-6555', 'CCU', 'DEL', 'SpiceJet', 7150.00),
    ('QP-1475', 'CCU', 'DEL', 'Akasa Air', 5920.00),
    ('IX-3077', 'CCU', 'DEL', 'Air India Express', 5549.00),
    ('AI-3567', 'CCU', 'DEL', 'Air India', 4909.00),
    ('SG-4658', 'CCU', 'DEL', 'SpiceJet', 5189.00),
    ('SG-7805', 'CCU', 'DEL', 'SpiceJet', 4830.00),
    ('AI-5833', 'CCU', 'DEL', 'Air India', 6560.00);

-- Delhi -> Hyderabad (DEL -> HYD) : 16 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('IX-608', 'DEL', 'HYD', 'Air India Express', 4050.00),
    ('6E-2596', 'DEL', 'HYD', 'IndiGo', 4480.00),
    ('6E-9340', 'DEL', 'HYD', 'IndiGo', 4240.00),
    ('6E-6143', 'DEL', 'HYD', 'IndiGo', 5419.00),
    ('6E-2783', 'DEL', 'HYD', 'IndiGo', 5840.00),
    ('UK-5211', 'DEL', 'HYD', 'Vistara', 4180.00),
    ('QP-6598', 'DEL', 'HYD', 'Akasa Air', 6179.00),
    ('IX-1769', 'DEL', 'HYD', 'Air India Express', 4489.00),
    ('IX-9372', 'DEL', 'HYD', 'Air India Express', 6400.00),
    ('AI-6171', 'DEL', 'HYD', 'Air India', 5079.00),
    ('AI-307', 'DEL', 'HYD', 'Air India', 5559.00),
    ('SG-7202', 'DEL', 'HYD', 'SpiceJet', 6560.00),
    ('UK-2606', 'DEL', 'HYD', 'Vistara', 6039.00),
    ('SG-8917', 'DEL', 'HYD', 'SpiceJet', 4449.00),
    ('SG-5380', 'DEL', 'HYD', 'SpiceJet', 5419.00),
    ('6E-4669', 'DEL', 'HYD', 'IndiGo', 6590.00);

-- Hyderabad -> Delhi (HYD -> DEL) : 18 flights
INSERT INTO flight (flight_number, source, destination, airline, price) VALUES
    ('6E-5611', 'HYD', 'DEL', 'IndiGo', 6299.00),
    ('AI-3079', 'HYD', 'DEL', 'Air India', 5409.00),
    ('SG-5676', 'HYD', 'DEL', 'SpiceJet', 4489.00),
    ('UK-9206', 'HYD', 'DEL', 'Vistara', 6679.00),
    ('AI-1502', 'HYD', 'DEL', 'Air India', 5449.00),
    ('6E-9196', 'HYD', 'DEL', 'IndiGo', 6199.00),
    ('6E-7442', 'HYD', 'DEL', 'IndiGo', 6109.00),
    ('AI-6725', 'HYD', 'DEL', 'Air India', 4059.00),
    ('IX-7853', 'HYD', 'DEL', 'Air India Express', 4589.00),
    ('QP-9117', 'HYD', 'DEL', 'Akasa Air', 5579.00),
    ('UK-4538', 'HYD', 'DEL', 'Vistara', 4999.00),
    ('UK-3255', 'HYD', 'DEL', 'Vistara', 4570.00),
    ('UK-3238', 'HYD', 'DEL', 'Vistara', 4149.00),
    ('6E-9759', 'HYD', 'DEL', 'IndiGo', 6209.00),
    ('QP-3280', 'HYD', 'DEL', 'Akasa Air', 5700.00),
    ('UK-5052', 'HYD', 'DEL', 'Vistara', 4499.00),
    ('AI-4594', 'HYD', 'DEL', 'Air India', 6069.00),
    ('AI-2168', 'HYD', 'DEL', 'Air India', 6989.00);
