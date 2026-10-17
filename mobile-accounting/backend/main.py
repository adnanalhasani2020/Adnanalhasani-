import os
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
import jwt
from fastapi import FastAPI, Depends, HTTPException
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from pydantic import BaseModel, Field
from pwdlib import PasswordHash
from sqlalchemy import create_engine, String, Integer, Boolean, DateTime, ForeignKey, select
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, Session, sessionmaker

DATABASE_URL=os.getenv("DATABASE_URL","sqlite:///./accounting.db")
SECRET_KEY=os.getenv("SECRET_KEY")
if not SECRET_KEY:
    if os.getenv("ENVIRONMENT","development")=="production": raise RuntimeError("SECRET_KEY is required")
    SECRET_KEY="LOCAL-ONLY-CHANGE-THIS-SECRET"
engine=create_engine(DATABASE_URL,connect_args={"check_same_thread":False} if DATABASE_URL.startswith("sqlite") else {},pool_pre_ping=True)
SessionLocal=sessionmaker(bind=engine,expire_on_commit=False)
hasher=PasswordHash.recommended()
oauth=OAuth2PasswordBearer(tokenUrl="/auth/token")
class Base(DeclarativeBase): pass
class User(Base):
    __tablename__="users"
    id:Mapped[int]=mapped_column(Integer,primary_key=True)
    username:Mapped[str]=mapped_column(String(80),unique=True,index=True)
    password_hash:Mapped[str]=mapped_column(String(255))
    active:Mapped[bool]=mapped_column(Boolean,default=True)
class Account(Base):
    __tablename__="accounts"
    id:Mapped[int]=mapped_column(Integer,primary_key=True)
    name:Mapped[str]=mapped_column(String(160))
    currency:Mapped[str]=mapped_column(String(3),default="YER")
    opening_minor:Mapped[int]=mapped_column(Integer,default=0)
    active:Mapped[bool]=mapped_column(Boolean,default=True)
class Transaction(Base):
    __tablename__="transactions"
    id:Mapped[int]=mapped_column(Integer,primary_key=True)
    account_id:Mapped[int]=mapped_column(ForeignKey("accounts.id"),index=True)
    kind:Mapped[str]=mapped_column(String(10))
    amount_minor:Mapped[int]=mapped_column(Integer)
    currency:Mapped[str]=mapped_column(String(3))
    description:Mapped[str]=mapped_column(String(300))
    created_by:Mapped[int]=mapped_column(ForeignKey("users.id"))
    created_at:Mapped[datetime]=mapped_column(DateTime(timezone=True),default=lambda:datetime.now(timezone.utc))
class Invoice(Base):
    __tablename__="invoices"
    id:Mapped[int]=mapped_column(Integer,primary_key=True)
    number:Mapped[str]=mapped_column(String(80),unique=True)
    customer:Mapped[str]=mapped_column(String(160))
    amount_minor:Mapped[int]=mapped_column(Integer)
    currency:Mapped[str]=mapped_column(String(3))
    created_by:Mapped[int]=mapped_column(ForeignKey("users.id"))
    created_at:Mapped[datetime]=mapped_column(DateTime(timezone=True),default=lambda:datetime.now(timezone.utc))
class Payment(Base):
    __tablename__="payments"
    id:Mapped[int]=mapped_column(Integer,primary_key=True)
    account_id:Mapped[int]=mapped_column(ForeignKey("accounts.id"),index=True)
    amount_minor:Mapped[int]=mapped_column(Integer)
    currency:Mapped[str]=mapped_column(String(3))
    direction:Mapped[str]=mapped_column(String(10))
    reference:Mapped[str]=mapped_column(String(160))
    state:Mapped[str]=mapped_column(String(20),default="recorded")
    created_by:Mapped[int]=mapped_column(ForeignKey("users.id"))
    created_at:Mapped[datetime]=mapped_column(DateTime(timezone=True),default=lambda:datetime.now(timezone.utc))
class AccountInput(BaseModel):
    name:str=Field(min_length=1,max_length=160)
    currency:str=Field(default="YER",min_length=3,max_length=3)
    opening_balance:Decimal=Decimal("0")
class TransactionInput(BaseModel):
    account_id:int
    kind:str
    amount:Decimal=Field(gt=0)
    currency:str=Field(default="YER",min_length=3,max_length=3)
    description:str=Field(min_length=1,max_length=300)
class InvoiceInput(BaseModel):
    invoice_number:str=Field(min_length=1,max_length=80)
    customer_name:str=Field(min_length=1,max_length=160)
    amount:Decimal=Field(gt=0)
    currency:str=Field(default="YER",min_length=3,max_length=3)
class PaymentInput(BaseModel):
    account_id:int
    amount:Decimal=Field(gt=0)
    currency:str=Field(default="YER",min_length=3,max_length=3)
    direction:str
    reference:str=Field(min_length=1,max_length=160)
def minor(v): return int((v*100).quantize(Decimal("1"),rounding=ROUND_HALF_UP))
def money(v): return f"{Decimal(v)/100:.2f}"
def db_session():
    with SessionLocal() as db: yield db
def user_required(token:str=Depends(oauth),db:Session=Depends(db_session)):
    try: uid=int(jwt.decode(token,SECRET_KEY,algorithms=["HS256"])["sub"])
    except Exception: raise HTTPException(401,"رمز الدخول غير صالح")
    u=db.get(User,uid)
    if not u or not u.active: raise HTTPException(401,"الحساب غير نشط")
    return u
app=FastAPI(title="Mobile Accounting API",docs_url=None if os.getenv("ENVIRONMENT")=="production" else "/docs")
@app.on_event("startup")
def startup():
    Base.metadata.create_all(engine)
    # Development-only convenience account. Production must set ADMIN_PASSWORD explicitly.
    is_production = os.getenv("ENVIRONMENT", "development").lower() == "production"
    password = os.getenv("ADMIN_PASSWORD")
    username = os.getenv("ADMIN_USERNAME", "admin")
    if is_production and not password:
        raise RuntimeError("ADMIN_PASSWORD is required in production")
    if not password and not is_production:
        password = "ChangeMe123!"
        print("WARNING: Development admin account enabled (admin / ChangeMe123!). Set ADMIN_PASSWORD before deployment.")
    if password:
        with SessionLocal() as db:
            existing = db.scalar(select(User).where(User.username == username))
            if not existing:
                db.add(User(username=username, password_hash=hasher.hash(password)))
                db.commit()
@app.get("/health")
def health(): return {"status":"ok"}
@app.post("/auth/token")
def login(form:OAuth2PasswordRequestForm=Depends(),db:Session=Depends(db_session)):
    u=db.scalar(select(User).where(User.username==form.username))
    if not u or not u.active or not hasher.verify(form.password,u.password_hash): raise HTTPException(401,"اسم المستخدم أو كلمة المرور غير صحيحة")
    return {"access_token":jwt.encode({"sub":str(u.id),"exp":datetime.now(timezone.utc)+timedelta(hours=12)},SECRET_KEY,algorithm="HS256"),"token_type":"bearer"}
def account_json(a,db):
    txs=db.scalars(select(Transaction).where(Transaction.account_id==a.id)).all()
    balance=a.opening_minor+sum(t.amount_minor if t.kind=="income" else -t.amount_minor for t in txs)
    return {"id":a.id,"name":a.name,"currency":a.currency,"balance":money(balance)}
@app.get("/summary")
def summary(user=Depends(user_required),db:Session=Depends(db_session)):
    accounts=db.scalars(select(Account).where(Account.active==True)).all()
    recent=db.scalars(select(Transaction).order_by(Transaction.id.desc()).limit(10)).all()
    alltx=db.scalars(select(Transaction)).all()
    income=sum(t.amount_minor for t in alltx if t.kind=="income");expenses=sum(t.amount_minor for t in alltx if t.kind=="expense")
    return {"accounts_count":len(accounts),"income":money(income),"expenses":money(expenses),"net":money(income-expenses),"recent_transactions":[{"kind":t.kind,"amount":money(t.amount_minor),"currency":t.currency,"description":t.description} for t in recent]}
@app.get("/accounts")
def accounts(user=Depends(user_required),db:Session=Depends(db_session)):
    return [account_json(a,db) for a in db.scalars(select(Account).order_by(Account.id.desc())).all()]
@app.post("/accounts")
def create_account(x:AccountInput,user=Depends(user_required),db:Session=Depends(db_session)):
    a=Account(name=x.name.strip(),currency=x.currency.upper(),opening_minor=minor(x.opening_balance));db.add(a);db.commit();db.refresh(a);return account_json(a,db)
@app.get("/transactions")
def transactions(user=Depends(user_required),db:Session=Depends(db_session)):
    return [{"id":t.id,"account_id":t.account_id,"kind":t.kind,"amount":money(t.amount_minor),"currency":t.currency,"description":t.description,"created_at":t.created_at.isoformat() if t.created_at else None} for t in db.scalars(select(Transaction).order_by(Transaction.id.desc()).limit(500)).all()]
@app.post("/transactions")
def create_transaction(x:TransactionInput,user=Depends(user_required),db:Session=Depends(db_session)):
    if x.kind not in ("income","expense"): raise HTTPException(422,"نوع الحركة غير صحيح")
    a=db.get(Account,x.account_id)
    if not a or not a.active: raise HTTPException(404,"الحساب غير موجود")
    if a.currency!=x.currency.upper(): raise HTTPException(422,"عملة الحركة يجب أن تطابق عملة الحساب")
    t=Transaction(account_id=a.id,kind=x.kind,amount_minor=minor(x.amount),currency=x.currency.upper(),description=x.description.strip(),created_by=user.id);db.add(t);db.commit();db.refresh(t)
    return {"id":t.id,"kind":t.kind,"amount":money(t.amount_minor),"description":t.description}
@app.get("/invoices")
def invoices(user=Depends(user_required),db:Session=Depends(db_session)):
    return [{"id":x.id,"invoice_number":x.number,"customer_name":x.customer,"amount":money(x.amount_minor),"currency":x.currency,"created_at":x.created_at.isoformat() if x.created_at else None} for x in db.scalars(select(Invoice).order_by(Invoice.id.desc()).limit(500)).all()]
@app.post("/invoices")
def create_invoice(x:InvoiceInput,user=Depends(user_required),db:Session=Depends(db_session)):
    number=x.invoice_number.strip()
    if db.scalar(select(Invoice).where(Invoice.number==number)): raise HTTPException(409,"رقم الفاتورة مستخدم")
    row=Invoice(number=number,customer=x.customer_name.strip(),amount_minor=minor(x.amount),currency=x.currency.upper(),created_by=user.id);db.add(row);db.commit();db.refresh(row)
    return {"id":row.id,"invoice_number":row.number,"customer_name":row.customer,"amount":money(row.amount_minor),"currency":row.currency}
@app.get("/payments")
def payments(user=Depends(user_required),db:Session=Depends(db_session)):
    return [{"id":x.id,"account_id":x.account_id,"amount":money(x.amount_minor),"currency":x.currency,"direction":x.direction,"reference":x.reference,"state":x.state,"created_at":x.created_at.isoformat() if x.created_at else None} for x in db.scalars(select(Payment).order_by(Payment.id.desc()).limit(500)).all()]
@app.post("/payments")
def create_payment(x:PaymentInput,user=Depends(user_required),db:Session=Depends(db_session)):
    if x.direction not in ("received","paid"): raise HTTPException(422,"نوع الدفعة غير صحيح")
    a=db.get(Account,x.account_id)
    if not a or not a.active: raise HTTPException(404,"الحساب غير موجود")
    if a.currency!=x.currency.upper(): raise HTTPException(422,"عملة الدفعة يجب أن تطابق عملة الحساب")
    row=Payment(account_id=a.id,amount_minor=minor(x.amount),currency=x.currency.upper(),direction=x.direction,reference=x.reference.strip(),created_by=user.id);db.add(row);db.commit();db.refresh(row)
    return {"id":row.id,"account_id":row.account_id,"amount":money(row.amount_minor),"currency":row.currency,"direction":row.direction,"reference":row.reference,"state":row.state}
