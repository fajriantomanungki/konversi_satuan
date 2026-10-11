"""Create the first admin account once; run with username/name/region prompts."""
import getpass, os
import main

name = input("Admin name: ").strip()
username = input("Username: ").strip()
kabupaten = input("Kabupaten/Kota: ").strip()
password = getpass.getpass("Password (min 12 chars): ")
if len(password) < 12 or not all((name, username, kabupaten)):
    raise SystemExit("Fill all fields and use a password with at least 12 characters.")
with main.connect() as db:
    if db.execute("SELECT COUNT(*) FROM users WHERE role='admin'").fetchone()[0]:
        raise SystemExit("An admin already exists; ask an admin to create accounts.")
    db.execute("INSERT INTO users VALUES(?,?,?,?,?,?)", (main.uuid.uuid4().hex, username, main.hash_password(password), name, "admin", kabupaten))
print("Admin created.")
