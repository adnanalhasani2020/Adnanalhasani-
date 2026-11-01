-- Deliver inter-organization invoice transfer events into the existing user notification inbox.
-- The trigger is transaction-bound: failed request/approval transactions do not emit notifications.
BEGIN;

CREATE OR REPLACE FUNCTION public.commercial_notify_invoice_purchase_request_event()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_title text;
  v_body text;
  v_org_id uuid;
  v_source_name text;
  v_destination_name text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'pending' THEN
      RETURN NEW;
    END IF;

    SELECT name INTO v_source_name
    FROM public.commercial_organizations
    WHERE id = NEW.source_organization_id;

    v_org_id := NEW.destination_organization_id;
    v_title := 'طلب فاتورة شراء جديد';
    v_body := 'أرسلت ' || coalesce(nullif(btrim(v_source_name), ''), 'مؤسسة تجارية')
      || ' طلب تحويل فاتورة رقم ' || NEW.source_invoice_number
      || '. راجع الطلب ووافق أو ارفض قبل إنشاء أمر الشراء.';
  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.status IS DISTINCT FROM NEW.status
       AND OLD.status = 'pending'
       AND NEW.status IN ('approved', 'rejected') THEN
      SELECT name INTO v_destination_name
      FROM public.commercial_organizations
      WHERE id = NEW.destination_organization_id;

      v_org_id := NEW.source_organization_id;
      IF NEW.status = 'approved' THEN
        v_title := 'تم قبول تحويل الفاتورة';
        v_body := 'وافقت ' || coalesce(nullif(btrim(v_destination_name), ''), 'المؤسسة المستقبلة')
          || ' على طلب تحويل الفاتورة رقم ' || NEW.source_invoice_number
          || '. أُنشئ أمر شراء مسودة ولم يتغير المخزون قبل الاستلام.';
      ELSE
        v_title := 'تم رفض تحويل الفاتورة';
        v_body := 'رفضت ' || coalesce(nullif(btrim(v_destination_name), ''), 'المؤسسة المستقبلة')
          || ' طلب تحويل الفاتورة رقم ' || NEW.source_invoice_number || '.';
      END IF;
    ELSE
      RETURN NEW;
    END IF;
  ELSE
    RETURN NEW;
  END IF;

  INSERT INTO public.commercial_user_notifications(
    user_id, organization_id, notification_type, title, body,
    entity_type, entity_id, priority, metadata
  )
  SELECT DISTINCT m.user_id, v_org_id, 'approval', v_title, left(v_body, 4000),
    'invoice_purchase_request', NEW.id, 'high',
    jsonb_build_object(
      'request_id', NEW.id,
      'source_organization_id', NEW.source_organization_id,
      'destination_organization_id', NEW.destination_organization_id,
      'source_sale_id', NEW.source_sale_id,
      'destination_purchase_order_id', NEW.destination_purchase_order_id,
      'request_status', NEW.status
    )
  FROM public.commercial_organization_members m
  WHERE m.organization_id = v_org_id
    AND m.state = 'active'
    AND m.role IN ('owner', 'admin', 'manager');

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS commercial_invoice_purchase_request_notification
  ON public.commercial_invoice_purchase_requests;
CREATE TRIGGER commercial_invoice_purchase_request_notification
AFTER INSERT OR UPDATE OF status
ON public.commercial_invoice_purchase_requests
FOR EACH ROW
EXECUTE FUNCTION public.commercial_notify_invoice_purchase_request_event();

REVOKE ALL ON FUNCTION public.commercial_notify_invoice_purchase_request_event()
  FROM PUBLIC, anon, authenticated;

COMMIT;
