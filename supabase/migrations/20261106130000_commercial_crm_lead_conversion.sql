BEGIN;

CREATE TABLE public.commercial_crm_lead_conversions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 activity_id uuid NOT NULL REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
 lead_id uuid NOT NULL,
 customer_id uuid NOT NULL REFERENCES public.commercial_customers(id) ON DELETE RESTRICT,
 converted_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 reused_existing_customer boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_crm_lead_conversions_lead_fk
  FOREIGN KEY(lead_id,organization_id,activity_id)
  REFERENCES public.commercial_crm_leads(id,organization_id,activity_id) ON DELETE RESTRICT,
 CONSTRAINT commercial_crm_lead_conversions_lead_uq UNIQUE(lead_id),
 CONSTRAINT commercial_crm_lead_conversions_idempotency_uq
  UNIQUE(organization_id,activity_id,converted_by,idempotency_key)
);
CREATE INDEX commercial_crm_lead_conversions_customer_idx
 ON public.commercial_crm_lead_conversions(organization_id,customer_id);

ALTER TABLE public.commercial_crm_lead_conversions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_crm_lead_conversions FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_lead_convert(
 p_lead_id uuid,p_expected_version integer,p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
 v_uid uuid:=auth.uid();
 v_lead public.commercial_crm_leads%ROWTYPE;
 v_role text;
 v_fingerprint text;
 v_existing public.commercial_crm_lead_conversions%ROWTYPE;
 v_customer_ids uuid[];
 v_customer_id uuid;
 v_reused boolean:=false;
 v_conversion_id uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_lead_id IS NULL OR p_expected_version IS NULL OR p_expected_version<1
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_crm_lead_conversion' USING ERRCODE='22023';
 END IF;

 SELECT * INTO v_lead FROM public.commercial_crm_leads l WHERE l.id=p_lead_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_lead_not_found' USING ERRCODE='P0002'; END IF;
 v_role:=public.commercial_crm_context_role(v_lead.organization_id,v_lead.activity_id);
 IF v_role='viewer' THEN RAISE EXCEPTION 'crm_lead_conversion_forbidden' USING ERRCODE='42501'; END IF;
 IF v_role='staff' AND v_lead.assigned_to<>v_uid AND v_lead.created_by<>v_uid THEN
  RAISE EXCEPTION 'crm_lead_conversion_access_denied' USING ERRCODE='42501';
 END IF;

 v_fingerprint:=md5(jsonb_build_object('lead_id',p_lead_id)::text);
 SELECT * INTO v_existing FROM public.commercial_crm_lead_conversions c
 WHERE c.organization_id=v_lead.organization_id AND c.activity_id=v_lead.activity_id
  AND c.converted_by=v_uid AND c.idempotency_key=btrim(p_idempotency_key);
 IF FOUND THEN
  IF v_existing.request_fingerprint<>v_fingerprint THEN
   RAISE EXCEPTION 'crm_lead_conversion_idempotency_conflict' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('id',v_existing.id,'lead_id',p_lead_id,'customer_id',v_existing.customer_id,
   'reused_existing_customer',v_existing.reused_existing_customer,'already_converted',true,'replayed',true);
 END IF;

 SELECT * INTO v_existing FROM public.commercial_crm_lead_conversions c WHERE c.lead_id=p_lead_id;
 IF FOUND THEN
  RETURN jsonb_build_object('id',v_existing.id,'lead_id',p_lead_id,'customer_id',v_existing.customer_id,
   'reused_existing_customer',v_existing.reused_existing_customer,'already_converted',true,'replayed',true);
 END IF;
 IF v_lead.version<>p_expected_version THEN
  RAISE EXCEPTION 'crm_lead_version_conflict' USING ERRCODE='40001';
 END IF;

 SELECT array_agg(matches.id ORDER BY matches.id) INTO v_customer_ids
 FROM (
  SELECT DISTINCT c.id
  FROM public.commercial_customers c
  WHERE c.organization_id=v_lead.organization_id AND c.state='active'
   AND (
    (nullif(lower(btrim(v_lead.email)),'') IS NOT NULL AND lower(btrim(coalesce(c.email,'')))=lower(btrim(v_lead.email)))
    OR
    (nullif(regexp_replace(coalesce(v_lead.phone,''),'[^0-9]','','g'),'') IS NOT NULL
     AND regexp_replace(coalesce(c.phone,''),'[^0-9]','','g')=regexp_replace(v_lead.phone,'[^0-9]','','g'))
   )
 ) matches;

 IF coalesce(cardinality(v_customer_ids),0)>1 THEN
  RAISE EXCEPTION 'crm_customer_match_ambiguous' USING ERRCODE='P0001';
 ELSIF coalesce(cardinality(v_customer_ids),0)=1 THEN
  v_customer_id:=v_customer_ids[1];
  v_reused:=true;
 ELSE
  INSERT INTO public.commercial_customers(
   organization_id,display_name,email,phone,external_reference,notes,state,created_by
  ) VALUES (
   v_lead.organization_id,
   left(v_lead.name,200),
   nullif(lower(btrim(v_lead.email)),''),
   nullif(btrim(v_lead.phone),''),
   'crm-lead:'||v_lead.id::text,
   nullif(left(concat_ws(E'\n',nullif(btrim(v_lead.company_name),''),nullif(btrim(v_lead.notes),'')),4000),''),
   'active',v_uid
  ) RETURNING id INTO v_customer_id;
 END IF;

 INSERT INTO public.commercial_crm_lead_conversions(
  organization_id,activity_id,lead_id,customer_id,converted_by,idempotency_key,request_fingerprint,reused_existing_customer
 ) VALUES (
  v_lead.organization_id,v_lead.activity_id,v_lead.id,v_customer_id,v_uid,
  btrim(p_idempotency_key),v_fingerprint,v_reused
 ) RETURNING id INTO v_conversion_id;

 INSERT INTO public.commercial_audit_events(
  organization_id,actor_user_id,action,entity_type,entity_id,metadata
 ) VALUES (
  v_lead.organization_id,v_uid,'crm.lead.converted','crm_lead_conversion',v_conversion_id::text,
  jsonb_build_object('lead_id',v_lead.id,'customer_id',v_customer_id,'reused_existing_customer',v_reused)
 );

 RETURN jsonb_build_object('id',v_conversion_id,'lead_id',v_lead.id,'customer_id',v_customer_id,
  'reused_existing_customer',v_reused,'already_converted',false,'replayed',false);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_crm_lead_convert(uuid,integer,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_lead_convert(uuid,integer,text) TO authenticated;

COMMIT;
