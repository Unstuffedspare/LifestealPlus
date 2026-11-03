# Fakes a server that ran LifeStealZ: plugins/LifeStealZ/userData.db in its schema, its
# config.yml, and a CSV export. Offline-mode UUIDs so the bots match their rows.
import hashlib, os, sqlite3, sys, uuid

def offline(name):
    return str(uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3))

folder = os.path.join(sys.argv[1], 'plugins', 'LifeStealZ')
os.makedirs(folder, exist_ok=True)
db = os.path.join(folder, 'userData.db')
if os.path.exists(db):
    os.remove(db)
c = sqlite3.connect(db)
c.execute("""CREATE TABLE hearts (uuid CHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, maxhp FLOAT NOT NULL DEFAULT 20.0,
             hasbeenRevived SMALLINT NOT NULL DEFAULT 0, craftedHearts SMALLINT UNSIGNED NOT NULL DEFAULT 0,
             craftedRevives SMALLINT UNSIGNED NOT NULL DEFAULT 0, killedOtherPlayers MEDIUMINT UNSIGNED NOT NULL DEFAULT 0,
             firstJoin BIGINT UNSIGNED NOT NULL)""")
rows = [('Alice', 30.0), ('Bob', 0.0), ('Cara', 51.0), ('Dora', 31.0)]
c.executemany("INSERT INTO hearts VALUES (?, ?, ?, 0, 0, 0, 0, 0)", [(offline(n), n, hp) for n, hp in rows])
c.commit()
c.close()
open(os.path.join(folder, 'config.yml'), 'w').write('minHearts: 0\nmaxHearts: 40\n')
open(os.path.join(folder, 'lifestealz.csv'), 'w').write(offline('Dora') + ',Dora,24.0,0,0,0,0,0\nnot a row\n')

# Our side: forget Dora, so the import has someone new to create.
ours = sqlite3.connect(os.path.join(sys.argv[1], 'plugins', 'LifestealPlus', 'lifesteal.db'))
ours.execute("DELETE FROM players WHERE name = 'Dora'")
ours.commit()
print('fake LifeStealZ ready')
