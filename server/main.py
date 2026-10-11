"""Konversi Satuan API. SQLite is suitable for a single-node pilot; use PostgreSQL in production."""
from __future__ import annotations
import json, os, sqlite3, uuid, hashlib, secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal
import jwt
from fastapi import Depends, FastAPI, Header, HTTPException, UploadFile, File
from pydantic import BaseModel, Field

DB_PATH = os.getenv("DATABASE_PATH", str(Path(__file__).with_name("konversi.db")))
SECRET = os.getenv("JWT_SECRET", "change-this-secret-before-deploying")
if len(SECRET) < 32:
    raise RuntimeError("Set JWT_SECRET to a random secret of at least 32 characters")

def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 310_000)
    return "pbkdf2_sha256$" + salt.hex() + "$" + digest.hex()

def verify_password(password: str, stored: str) -> bool:
    try:
        _, salt, digest = stored.split("$")
        check = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 310_000).hex()
        return secrets.compare_digest(check, digest)
    except ValueError:
        return False
app = FastAPI(title="Konversi Satuan API", version="1.0.0")

class User(BaseModel):
    id: str
    name: str
    role: Literal["admin", "pengawas", "petugas"]
    kabupaten: str

class LoginInput(BaseModel):
    username: str
    password: str

class UserCreate(LoginInput):
    name: str
    role: Literal["admin", "pengawas", "petugas"]
    kabupaten: str

class Conversion(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    commodity: str = Field(min_length=1, max_length=120)
    quality: str = Field(default="Umum", max_length=80)
    localUnit: str = Field(min_length=1, max_length=40)
    localValue: float = Field(gt=0)
    standardUnit: str = Field(min_length=1, max_length=40)
    standardValue: float = Field(gt=0)
    localPrice: int = Field(gt=0)
    kabupaten: str = Field(min_length=1, max_length=100)
    approved: bool = False
    contributors: int = 1
    updatedAt: int = 0

class Catalog(BaseModel):
    schemaVersion: int = 1
    exportedAt: int | None = None
    conversions: list[Conversion]


def connect():
    c = sqlite3.connect(DB_PATH, timeout=10)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("""CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, password TEXT NOT NULL,
      name TEXT NOT NULL, role TEXT NOT NULL, kabupaten TEXT NOT NULL)""")
    c.execute("""CREATE TABLE IF NOT EXISTS conversions (
      id TEXT PRIMARY KEY, commodity TEXT NOT NULL, quality TEXT NOT NULL,
      localUnit TEXT NOT NULL, localValue REAL NOT NULL, standardUnit TEXT NOT NULL,
      standardValue REAL NOT NULL, localPrice INTEGER NOT NULL, kabupaten TEXT NOT NULL,
      approved INTEGER NOT NULL DEFAULT 0, contributors INTEGER NOT NULL DEFAULT 1,
      updatedAt INTEGER NOT NULL, submittedBy TEXT NOT NULL)""")
    c.commit()
    return c


def current_user(authorization: str | None = Header(default=None)) -> User:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(401, "Bearer token required")
    try:
        claims = jwt.decode(authorization[7:], SECRET, algorithms=["HS256"])
    except jwt.PyJWTError:
        raise HTTPException(401, "Invalid or expired token")
    return User(**claims["user"])


def require(user: User, *roles: str):
    if user.role not in roles:
        raise HTTPException(403, "Role is not allowed for this action")

@app.get("/health")
def health(): return {"status": "ok", "service": "konversi-satuan"}

@app.post("/v1/login")
def login(body: LoginInput):
    with connect() as db:
        row = db.execute("SELECT * FROM users WHERE username=?", (body.username,)).fetchone()
    if row is None or not verify_password(body.password, row["password"]):
        raise HTTPException(401, "Username or password is incorrect")
    user = User(id=row["id"], name=row["name"], role=row["role"], kabupaten=row["kabupaten"])
    token = jwt.encode({"user": user.model_dump(), "exp": datetime.now(timezone.utc) + timedelta(days=14)}, SECRET, algorithm="HS256")
    return {"accessToken": token, "user": user}

@app.post("/v1/users", status_code=201)
def create_user(body: UserCreate, admin: User = Depends(current_user)):
    require(admin, "admin")
    if body.role != "admin" and body.kabupaten != admin.kabupaten:
        raise HTTPException(403, "Admin can only create users in their kabupaten")
    try:
        with connect() as db:
            db.execute("INSERT INTO users VALUES(?,?,?,?,?,?)", (str(uuid.uuid4()), body.username, hash_password(body.password), body.name, body.role, body.kabupaten))
    except sqlite3.IntegrityError:
        raise HTTPException(409, "Username already exists")
    return {"created": True}

@app.post("/v1/submissions", response_model=Catalog)
def submit(items: list[Conversion], user: User = Depends(current_user)):
    require(user, "pengawas")
    if not items: return Catalog(conversions=[])
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    with connect() as db:
        for item in items:
            if item.kabupaten != user.kabupaten:
                raise HTTPException(403, "Submission kabupaten does not match the account")
            values = item.model_dump()
            values.update(approved=False, updatedAt=now)
            db.execute("""INSERT OR REPLACE INTO conversions
              (id,commodity,quality,localUnit,localValue,standardUnit,standardValue,localPrice,kabupaten,approved,contributors,updatedAt,submittedBy)
              VALUES(:id,:commodity,:quality,:localUnit,:localValue,:standardUnit,:standardValue,:localPrice,:kabupaten,:approved,:contributors,:updatedAt,:submittedBy)""",
              {**values, "submittedBy": user.id})
    return Catalog(conversions=[])

@app.get("/v1/pending", response_model=Catalog)
def pending(user: User = Depends(current_user)):
    require(user, "admin")
    with connect() as db:
        rows = db.execute("SELECT * FROM conversions WHERE approved=0 AND kabupaten=? ORDER BY commodity", (user.kabupaten,)).fetchall()
    return Catalog(conversions=[record(r) for r in rows])

@app.post("/v1/approve/{commodity}", response_model=Catalog)
def approve(commodity: str, quality: str, localUnit: str, standardUnit: str, user: User = Depends(current_user)):
    require(user, "admin")
    with connect() as db:
        rows = db.execute("SELECT * FROM conversions WHERE approved=0 AND kabupaten=? AND lower(commodity)=lower(?) AND lower(quality)=lower(?) AND lower(localUnit)=lower(?) AND lower(standardUnit)=lower(?) ORDER BY updatedAt", (user.kabupaten, commodity, quality, localUnit, standardUnit)).fetchall()
        groups: dict[tuple[str,str,str,str], list[sqlite3.Row]] = {}
        for row in rows: groups.setdefault((row["commodity"].strip().lower(), row["quality"].strip().lower(), row["localUnit"].strip().lower(), row["standardUnit"].strip().lower()), []).append(row)
        final = []
        for group in groups.values():
            anchor = group[0]
            ratio = sum(r["standardValue"] / r["localValue"] for r in group) / len(group)
            price = round(sum(r["localPrice"] for r in group) / len(group))
            db.execute("DELETE FROM conversions WHERE id IN (%s)" % ",".join("?" for _ in group), [r["id"] for r in group])
            row = dict(anchor); row.update(standardValue=ratio * anchor["localValue"], localPrice=price, approved=1, contributors=len(group), updatedAt=int(datetime.now(timezone.utc).timestamp()*1000))
            db.execute("""INSERT INTO conversions(id,commodity,quality,localUnit,localValue,standardUnit,standardValue,localPrice,kabupaten,approved,contributors,updatedAt,submittedBy)
              VALUES(:id,:commodity,:quality,:localUnit,:localValue,:standardUnit,:standardValue,:localPrice,:kabupaten,:approved,:contributors,:updatedAt,:submittedBy)""", {**row, "submittedBy": user.id})
            final.append(record(row))
    return Catalog(conversions=final)

@app.get("/v1/approved", response_model=Catalog)
def approved(user: User = Depends(current_user)):
    with connect() as db:
        rows = db.execute("SELECT * FROM conversions WHERE approved=1 AND kabupaten=? ORDER BY commodity", (user.kabupaten,)).fetchall()
    return Catalog(conversions=[record(r) for r in rows])

@app.post("/v1/catalog/import", response_model=Catalog)
def import_catalog(file: UploadFile = File(...), user: User = Depends(current_user)):
    require(user, "admin")
    try:
        data = Catalog.model_validate_json(file.file.read())
    except Exception as exc:
        raise HTTPException(400, f"Invalid catalog JSON: {exc}")
    now = int(datetime.now(timezone.utc).timestamp()*1000)
    with connect() as db:
        for item in data.conversions:
            if item.kabupaten != user.kabupaten:
                raise HTTPException(403, "Catalog contains another kabupaten")
            row = item.model_dump(); row.update(approved=True, updatedAt=now)
            db.execute("""INSERT OR REPLACE INTO conversions
              (id,commodity,quality,localUnit,localValue,standardUnit,standardValue,localPrice,kabupaten,approved,contributors,updatedAt,submittedBy)
              VALUES(:id,:commodity,:quality,:localUnit,:localValue,:standardUnit,:standardValue,:localPrice,:kabupaten,:approved,:contributors,:updatedAt,:submittedBy)""", {**row, "submittedBy":user.id})
    return Catalog(conversions=data.conversions)

def record(row):
    return Conversion(**{key: row[key] for key in Conversion.model_fields if key in row.keys()})
