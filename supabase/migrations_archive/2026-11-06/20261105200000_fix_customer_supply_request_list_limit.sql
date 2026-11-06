BEGIN;
-- Apply the row limit before aggregation; LIMIT on the aggregate's single output row
-- does not constrain the number of requests returned.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_organization_supply_requests(
 p_organization_id uuid,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 OR NOT EXISTS(
  SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
   AND m.user_id=v_uid AND m.state='active' AND m.role IN('owner','admin','manager')
 ) THEN RAISE EXCEPTION 'customer_supply_request_management_forbidden' USING ERRCODE='42501'; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object(
  'id',r.id,'customer_name',c.display_name,'item_name',r.item_name,'specifications',r.specifications,
  'quantity',r.requested_quantity,'unit_code',r.unit_code,'unit_name',r.unit_name,
  'requested_price_minor',r.requested_price_minor,'currency_code',r.currency_code,
  'payment_mode',r.payment_mode,'customer_note',r.customer_note,'status',r.status,
  'merchant_note',r.merchant_note,'purchase_order_id',r.purchase_order_id,
  'version',r.version,'created_at',r.created_at,'updated_at',r.updated_at
 ) ORDER BY r.created_at DESC,r.id),'[]'::jsonb) INTO v_result
 FROM (
  SELECT request.*
  FROM public.commercial_customer_supply_requests request
  WHERE request.organization_id=p_organization_id AND request.status<>'converted'
  ORDER BY request.created_at DESC,request.id
  LIMIT p_limit
 ) r
 JOIN public.commercial_customers c ON c.id=r.customer_id AND c.organization_id=r.organization_id;
 RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_organization_supply_requests(uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_organization_supply_requests(uuid,integer) TO authenticated;
COMMIT;
